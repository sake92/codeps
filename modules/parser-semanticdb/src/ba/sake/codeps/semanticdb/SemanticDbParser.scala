package ba.sake.codeps.semanticdb

import ba.sake.codeps.model.{DepsGraph, Edge, Node, NodeKind}
import scala.meta.internal.semanticdb.{Range, SymbolInformation, TextDocument, TextDocuments}

object SemanticDbParser:

  /**
    * Parses the bytes of one .semanticdb file (a TextDocuments protobuf payload) into the
    * graph of its defined symbols and references between them. References to symbols not
    * defined in this file (external) are emitted as edges too — call
    * `DepsGraph.withoutDanglingEdges` after merging all files to drop them.
    * `root` is the workspace root used to make source URIs relative.
    */
  def parse(bytes: Array[Byte], root: java.nio.file.Path): Either[String, DepsGraph] =
    try
      val docs = TextDocuments.parseFrom(bytes)
      var nodes = Set.empty[Node]
      var edges = Set.empty[Edge]
      for doc <- docs.documents do
        nodes ++= documentNodes(doc, root)
        edges ++= documentEdges(doc, root)
      Right(DepsGraph(nodes, edges))
    catch
      case e: Exception => Left(s"failed to parse semanticdb: ${e.getMessage}")

  // ---------- nodes ----------

  private def documentNodes(doc: TextDocument, root: java.nio.file.Path): Set[Node] =
    val file = fileId(doc.uri, root)
    var nodes = Set.empty[Node]
    nodes += Node(file, NodeKind.file)
    for s <- doc.symbols do
      symbolNodeId(s).foreach { case (id, kind, parentId) =>
        kind match
          case NodeKind.`package` | NodeKind.file => nodes += Node(id, kind)
          case _                                  => nodes += Node(id, kind, parentId, Some(file))
      }
    // package declarations (e.g. "com/example/util/") are emitted as definition occurrences, not symbols
    for occ <- doc.occurrences do
      if occ.role.isDefinition && occ.symbol.endsWith("/") then
        nodes += Node(occ.symbol.stripSuffix("/").replace('/', '.'), NodeKind.`package`)
    nodes

  /** Node (id, kind, parentId) of a defined symbol, if it is not noise (locals, params, constructors). */
  private def symbolNodeId(s: SymbolInformation): Option[(String, NodeKind, Option[String])] =
    val sym = s.symbol
    if sym.endsWith("/") then Some((sym.stripSuffix("/").replace('/', '.'), NodeKind.`package`, None)) // package decl
    else if s.kind.isLocal then None
    else if isConstructor(sym) then None
    else if isTypeKind(s.kind) then Some((typeId(sym), NodeKind.`type`, parentOf(sym)))
    else if isMemberKind(s.kind) then Some((memberId(sym), NodeKind.member, parentOf(sym)))
    else None // PARAMETER, SELF_PARAMETER, TYPE_PARAMETER, ... — also PACKAGE_OBJECT: package objects ("foo.package.") are deliberately not emitted as nodes, so references to them dangle and are pruned by withoutDanglingEdges; acceptable because they are rare and their members still surface

  private def isTypeKind(k: SymbolInformation.Kind): Boolean =
    k.isType || k.isClass || k.isTrait || k.isObject

  private def isMemberKind(k: SymbolInformation.Kind): Boolean =
    k.isField || k.isMethod || k.isMacro

  /** `<init>` constructors are not nodes; references to them resolve to the parent type.
    * Scala 3 emits them backticked: ``Foo#`<init>`().`` */
  private def isConstructor(sym: String): Boolean =
    val idx = sym.lastIndexOf('#')
    idx >= 0 && sym.substring(idx + 1).replace("`", "").startsWith("<init>")

  /** `com/example/a/Foo#` -> `com.example.a.Foo`; `com/example/a/Outer#Inner#` -> `com.example.a.Outer#Inner`;
    * `com/example/a/Foo.` (object) -> `com.example.a.Foo`. */
  private def typeId(sym: String): String =
    sym.stripSuffix("#").stripSuffix(".").replace('/', '.')

  /**
    * `com/example/a/Foo#doWork().` -> `com.example.a.Foo#doWork` (type member, no dot in last segment)
    * `com/example/app/Main.main().` -> `com.example.app.Main#main` (object member: dot INSIDE last segment becomes `#`)
    * `com/example/a/topLevelHelper.` -> `com.example.a.topLevelHelper` (top-level member)
    * `org/thirdparty/Ext.` -> `org.thirdparty.Ext` (object itself: no dot in last segment)
    */
  private def memberId(sym: String): String =
    val stripped = sym.stripSuffix(".")
    val cut = stripped.indexOf('(')
    val withoutParams = if cut >= 0 then stripped.substring(0, cut) else stripped
    val slashIdx = withoutParams.lastIndexOf('/')
    if slashIdx < 0 then withoutParams
    else
      val pkgPart = withoutParams.substring(0, slashIdx).replace('/', '.')
      val seg = withoutParams.substring(slashIdx + 1)
      val dotIdx = seg.indexOf('.')
      if dotIdx >= 0 then s"$pkgPart.${seg.substring(0, dotIdx)}#${seg.substring(dotIdx + 1)}"
      else s"$pkgPart.$seg"

  /**
    * Nearest enclosing node of a symbol: for `#`-members the declaring type,
    * for types the enclosing type (nested) or the package, for object members
    * the object, for top-level members the package.
    */
  private def parentOf(sym: String): Option[String] =
    if sym.endsWith("#") then
      // type symbol: enclosing type if nested, else package
      val withoutHash = sym.stripSuffix("#")
      val innerIdx = withoutHash.lastIndexOf('#')
      if innerIdx >= 0 then Some(withoutHash.substring(0, innerIdx).replace('/', '.'))
      else packagePart(withoutHash)
    else
      val idx = sym.lastIndexOf('#')
      if idx >= 0 then
        // member of a class ("…/Service2#run()."): the declaring type; object nested in a class ("…/Outer#Inner.m()."): the nested object
        val rest = sym.stripSuffix(".").substring(idx + 1)
        val dotIdx = rest.lastIndexOf('.')
        if dotIdx >= 0 then Some((sym.substring(0, idx + 1) + rest.substring(0, dotIdx)).replace('/', '.'))
        else Some(sym.substring(0, idx).replace('/', '.'))
      else
        // object member ("…/Main.main().") or object ("…/Main."): the owner; top-level member ("…/util/foo."): the package
        val stripped = sym.stripSuffix(".")
        val dotIdx = stripped.lastIndexOf('.')
        if dotIdx >= 0 then Some(stripped.substring(0, dotIdx).replace('/', '.'))
        else packagePart(stripped)

  private def packagePart(symNoTerminator: String): Option[String] =
    val slashIdx = symNoTerminator.lastIndexOf('/')
    if slashIdx >= 0 then Some(symNoTerminator.substring(0, slashIdx).replace('/', '.')) else None

  // ---------- edges ----------

  private def documentEdges(doc: TextDocument, root: java.nio.file.Path): Set[Edge] =
    val fallbackFile = fileId(doc.uri, root)
    val defined = definedSymbols(doc)
    var edges = Set.empty[Edge]
    for occ <- doc.occurrences do
      if occ.role.isReference then
        targetId(occ.symbol).foreach { t =>
          sourceId(defined, occ.range, fallbackFile).foreach { s =>
            if s != t then edges += Edge(s, t)
          }
        }
    edges

  /** Node id the occurrence points at; None for locals, package decls (already nodes), and unresolvable symbols. */
  private def targetId(sym: String): Option[String] =
    if sym.isEmpty || sym.endsWith("/") || isLocalSymbol(sym) then None
    else if isConstructor(sym) then parentOf(sym) // dependency on `new Foo` == dependency on Foo
    else if sym.endsWith("#") then Some(typeId(sym))
    else Some(memberId(sym))

  /**
    * Defined, non-noise symbols of a document with the position of their definition
    * (the defining occurrence's range), in source order. SymbolInformation carries no
    * ranges and the document text is usually absent, so the definition position is the
    * only anchor available: the innermost symbol containing a reference is the last
    * definition that precedes it.
    * Deliberate limitation: a reference at object/class top level that appears AFTER a
    * nested class (or a member definition) is attributed to the previous sibling
    * definition rather than to the object/class itself — bounded mis-attribution, never
    * a wrong target id; deliberate because semanticdb documents in the wild have empty
    * `text` and definition ranges covering only names, so range containment is not possible.
    */
  private def definedSymbols(doc: TextDocument): Vector[(Range, String)] =
    val kinds = doc.symbols.iterator.map(s => s.symbol -> s.kind).toMap
    doc.occurrences.iterator
      .filter(o => o.role.isDefinition && o.range.nonEmpty && o.symbol.nonEmpty)
      .filter(o => !o.symbol.endsWith("/")) // package declarations
      .filter(o => !isConstructor(o.symbol))
      .filter(o => !isLocalSymbol(o.symbol))
      .filter(o => !kinds.get(o.symbol).exists(k => k.isParameter || k.isSelfParameter || k.isTypeParameter))
      .map(o => (o.range.get, o.symbol))
      .toVector
      .sortBy { case (r, _) => (r.startLine, r.startCharacter) }

  /** Innermost defined symbol preceding the occurrence; falls back to the file node. */
  private def sourceId(defined: Vector[(Range, String)], occRange: Option[Range], fallbackFile: String): Option[String] =
    occRange match
      case None => Some(fallbackFile)
      case Some(o) =>
        defined
          .filter { case (defRange, _) =>
            defRange.startLine < o.startLine ||
              (defRange.startLine == o.startLine && defRange.startCharacter <= o.startCharacter)
          }
          .lastOption
          .map { case (_, sym) => if sym.endsWith("#") then typeId(sym) else memberId(sym) }
          .orElse(Some(fallbackFile))

  /** Source URI relative to the workspace root; relative URIs kept as-is; absolute URIs outside the root fall back to the file name. */
  private def fileId(uri: String, root: java.nio.file.Path): String =
    val p = java.nio.file.Paths.get(uri)
    if p.isAbsolute && p.startsWith(root) then root.relativize(p).toString
    else if !p.isAbsolute then p.toString
    else p.getFileName.toString

  /** SemanticDB names synthetically introduced locals `local0`, `local1`, ... */
  private def isLocalSymbol(sym: String): Boolean =
    sym.startsWith("local") && sym.length > 5 && sym.drop(5).forall(_.isDigit)
