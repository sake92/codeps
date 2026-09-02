package ba.sake.codeps.semanticdb

import ba.sake.codeps.model.{DeclarationSurface, DepsGraph, Edge, Node, NodeKind}
import scala.meta.internal.semanticdb.*

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
    // raw symbols of all sealed types: members whose owner chain (or class parents)
    // contains one of these belong to a sealed hierarchy
    val sealedOwners = doc.symbols.iterator.filter(_.isSealed).map(_.symbol).toSet
    var nodes = Set.empty[Node]
    var fileSurface = DeclarationSurface()
    for s <- doc.symbols do
      val node = symbolNode(s, file, sealedOwners)
      node.foreach(nodes += _)
      // Class-private declarations are intentionally not dependency nodes, but
      // they still belong to the file's declaration surface. Visible symbols
      // carry their own counter and are summed into the file by Aggregator.
      if node.isEmpty then declarationSurfaceOf(s).foreach(surface => fileSurface = fileSurface + surface)
    nodes += Node(file, NodeKind.file, declarationSurface = fileSurface)
    // package declarations (e.g. "com/example/util/") are emitted as definition occurrences, not symbols
    for occ <- doc.occurrences do
      if occ.role.isDefinition && occ.symbol.endsWith("/") then
        nodes += Node(occ.symbol.stripSuffix("/").replace('/', '.'), NodeKind.`package`)
    nodes

  /** Node of a defined symbol, if it is not noise (locals, params, constructors).
    * Class-scoped private symbols (`private`, `private[this]`) are skipped entirely: they are
    * implementation details that can never create cross-package dependencies, so they only add
    * noise at the member/type levels. Their references are collapsed into the nearest non-private
    * ancestor (see `collapseUp`). Package-private (`private[pkg]`) and `protected` symbols are kept,
    * but are NOT exposed (`isExposed = false`): they are not part of the externally visible surface.
    * `isExposed`/`ports`/`mutPorts` are resolved here — the Scala adapter's weight rules
    * (sealed/given/var/...) never leak into the metrics layer. */
  private def symbolNode(s: SymbolInformation, file: String, sealedOwners: Set[String]): Option[Node] =
    val sym = s.symbol
    if sym.endsWith("/") then Some(Node(sym.stripSuffix("/").replace('/', '.'), NodeKind.`package`))
    else if isClassPrivate(s.access) then None
    else if s.kind.isLocal then None
    else if isConstructor(sym) then None
    else if isTypeKind(s.kind) then Some(typeNode(s, file, sealedOwners))
    else if isMemberKind(s.kind) then Some(memberNode(s, file, sealedOwners))
    else None // PARAMETER, SELF_PARAMETER, TYPE_PARAMETER, ... — also PACKAGE_OBJECT: package objects ("foo.package.") are deliberately not emitted as nodes, so references to them dangle and are pruned by withoutDanglingEdges; acceptable because they are rare and their members still surface

  private def typeNode(s: SymbolInformation, file: String, sealedOwners: Set[String]): Node =
    Node(typeId(s.symbol), NodeKind.`type`, parentOf(s.symbol), Some(file),
      isExposed = isExposed(s), ports = portsOf(s, sealedOwners), mutPorts = mutPortsOf(s),
      declarationSurface = declarationSurfaceOf(s).getOrElse(DeclarationSurface()))

  private def memberNode(s: SymbolInformation, file: String, sealedOwners: Set[String]): Node =
    Node(memberId(s.symbol), NodeKind.member, parentOf(s.symbol), Some(file),
      isExposed = isExposed(s), ports = portsOf(s, sealedOwners), mutPorts = mutPortsOf(s),
      declarationSurface = declarationSurfaceOf(s).getOrElse(DeclarationSurface()))

  /** Counts source declarations independently of the weighted public-port
    * calculation. Setters are compiler accessors, not declarations; locals,
    * constructors and unsupported SemanticDB kinds are not surface entries. */
  private def declarationSurfaceOf(s: SymbolInformation): Option[DeclarationSurface] =
    if isConstructor(s.symbol) || s.kind.isLocal || s.displayName.endsWith("_=") then None
    else if isTypeKind(s.kind) || isMemberKind(s.kind) then
      val mutable = isMutableDeclaration(s)
      val one = if mutable then 1 else 0
      s.access match
        case Access.Empty | _: PublicAccess => Some(DeclarationSurface(public = 1, publicMutable = one))
        case _: ProtectedAccess             => Some(DeclarationSurface(`protected` = 1, protectedMutable = one))
        case _: PrivateWithinAccess        => Some(DeclarationSurface(packageRestricted = 1, packageRestrictedMutable = one))
        case _: PrivateAccess | _: PrivateThisAccess =>
          Some(DeclarationSurface(privateMembers = 1, privateMutable = one))
        case _ => Some(DeclarationSurface(privateMembers = 1, privateMutable = one))
    else None

  /** Mutable declaration rules intentionally match `mutPortsOf`, but without
    * requiring public exposure: private mutable state is still useful evidence
    * for encapsulation metrics. */
  private def isMutableDeclaration(s: SymbolInformation): Boolean =
    !s.isGiven && !s.isImplicit && (s.isVar || isMutableCollectionType(s.signature))

  // ---------- exposure (the Scala adapter's weight rules) ----------

  /** Public API surface: default (no modifier) or explicit `public` access counts as
    * exposed. `private[pkg]` and `protected` members are internal to the package or
    * subclass world and are NOT part of the externally visible surface. Var setters
    * (`x_=`) are compiler accessors for a `var` — the getter represents the member,
    * so the setter is never part of the surface (it still exists as a node, so
    * assignment edges stay visible). */
  private def isExposed(s: SymbolInformation): Boolean =
    if s.displayName.endsWith("_=") then false
    else
      s.access match
        case Access.Empty | _: PublicAccess => true
        case _                             => false

  /** Weighted exposure contribution: types 3, defs/vals 1, members of a sealed
    * hierarchy 0.5 (external code cannot extend them, so the effective surface is
    * smaller), givens/implicits a flat +1 (ambiently public via implicit search —
    * separate accounting, never folded into the type/def weight). */
  private def portsOf(s: SymbolInformation, sealedOwners: Set[String]): Double =
    if !isExposed(s) then 0.0
    else if s.isGiven || s.isImplicit then 1.0
    else if s.isSealed || inSealedHierarchy(s, sealedOwners) then 0.5
    else if s.kind.isField || s.kind.isMethod || s.kind.isMacro then 1.0
    else 3.0 // types

  /** Mutable-state exposure: a `var`, or a val/def whose type is a known mutable
    * collection (`scala.collection.mutable.*`, `scala.Array`) — a coupling channel
    * that never shows up as a graph edge. Givens/implicits are never mutable state:
    * the compiler marks them `VAR` spuriously, and Scala 3 does not allow `given var`. */
  private def mutPortsOf(s: SymbolInformation): Double =
    if !isExposed(s) || s.isGiven || s.isImplicit then 0.0
    else if s.isVar then 1.0
    else if isMutableCollectionType(s.signature) then 1.0
    else 0.0

  /** True when the symbol is sealed itself, its owner chain contains a sealed type
    * (a member of a sealed hierarchy), or one of its class parents is sealed (a
    * subtype within the hierarchy). */
  private def inSealedHierarchy(s: SymbolInformation, sealedOwners: Set[String]): Boolean =
    var cur = rawOwnerOf(s.symbol)
    var guard = 0
    while cur.nonEmpty && guard < 100 do
      if sealedOwners.contains(cur.get) then return true
      cur = rawOwnerOf(cur.get)
      guard += 1
    s.signature match
      case sig: ClassSignature =>
        sig.parents.exists {
          case tr: TypeRef => sealedOwners.contains(tr.symbol)
          case _           => false
        }
      case _ => false

  private def isMutableCollectionType(sig: Signature): Boolean = sig match
    case v: ValueSignature     => isMutableCollection(v.tpe)
    case m: MethodSignature    => isMutableCollection(m.returnType) // the return type
    case _                     => false

  private def isMutableCollection(tpe: Type): Boolean = tpe match
    case tr: TypeRef =>
      tr.symbol == "scala/Array#" || tr.symbol.startsWith("scala/collection/mutable/")
    case bt: ByNameType => isMutableCollection(bt.tpe) // def without parens: by-name value
    case _              => false

  /** True for class-scoped private access (`private`, `private[this]`); `private[pkg]` and `protected` are kept. */
  private def isClassPrivate(access: Access): Boolean =
    access match
      case _: PrivateAccess | _: PrivateThisAccess => true
      case _                                       => false

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
    // symbol -> access, keyed by raw symbol (the owner walk stays in raw symbol space)
    val accessOf = doc.symbols.iterator.map(s => s.symbol -> s.access).toMap
    var edges = Set.empty[Edge]
    for occ <- doc.occurrences do
      if occ.role.isReference then
        targetId(occ.symbol, accessOf).foreach { t =>
          sourceId(defined, occ.range, fallbackFile, accessOf).foreach { s =>
            if s != t then edges += Edge(s, t)
          }
        }
    edges

  /** Node id the occurrence points at; None for locals, package decls (already nodes), unresolvable
    * symbols, and references to class-private symbols that collapse into a package (dropped: they
    * are file-scoped, so the edge carries no cross-file information).
    */
  private def targetId(sym: String, accessOf: Map[String, Access]): Option[String] =
    if sym.isEmpty || sym.endsWith("/") || isLocalSymbol(sym) then None
    else if isConstructor(sym) then parentOf(sym) // dependency on `new Foo` == dependency on Foo
    else if isClassPrivate(accessOf.getOrElse(sym, Access.Empty)) then
      collapseUp(sym, accessOf).filter(!_.endsWith("/")).map(dotFormId)
    else Some(targetIdPlain(sym))

  private def targetIdPlain(sym: String): String =
    if sym.endsWith("#") then typeId(sym)
    else memberId(sym)

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
    * Class-private definitions stay as anchors (attribution stays precise); the edge
    * source is collapsed up afterwards.
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

  /** Innermost defined symbol preceding the occurrence; falls back to the file node.
    * A class-private source is collapsed into its nearest non-private ancestor; when that
    * lands on a package (top-level private symbol) the file node is used instead, so the
    * file's dependencies stay visible at file/package level.
    */
  private def sourceId(
      defined: Vector[(Range, String)],
      occRange: Option[Range],
      fallbackFile: String,
      accessOf: Map[String, Access]
  ): Option[String] =
    occRange match
      case None => Some(fallbackFile)
      case Some(o) =>
        defined
          .filter { case (defRange, _) =>
            defRange.startLine < o.startLine ||
              (defRange.startLine == o.startLine && defRange.startCharacter <= o.startCharacter)
          }
          .lastOption
          .map { case (_, sym) =>
            val id = if sym.endsWith("#") then typeId(sym) else memberId(sym)
            if isClassPrivate(accessOf.getOrElse(sym, Access.Empty)) then
              collapseUp(sym, accessOf) match
                case Some(up) if !up.endsWith("/") => dotFormId(up)
                case _                             => fallbackFile // top-level private: attribute to the file
            else id
          }
          .orElse(Some(fallbackFile))

  /**
    * Walks the owner chain of a class-private symbol (in raw symbol space: `#`-suffixed types,
    * `.`-suffixed members/objects, `/`-suffixed packages) until a non-private ancestor is found.
    * Returns None when the chain is exhausted; a top-level private symbol resolves to its
    * package (raw symbol ending in '/'), which callers treat as "file-scoped".
    */
  private def collapseUp(sym: String, accessOf: Map[String, Access]): Option[String] =
    var cur = sym
    var guard = 0
    while isClassPrivate(accessOf.getOrElse(cur, Access.Empty)) && guard < 100 do
      rawOwnerOf(cur) match
        case Some(owner) => cur = owner
        case None        => return None
      guard += 1
    Some(cur)

  /** Raw owner symbol of a symbol; None for packages and symbols without an owner.
    * `com/example/a/Foo#m().` -> `com/example/a/Foo#`, `com/example/a/O.m().` -> `com/example/a/O.`,
    * `com/example/a/Foo#` -> `com/example/a/`, `com/example/a/topLevelHelper.` -> `com/example/a/`.
    */
  private def rawOwnerOf(sym: String): Option[String] =
    if sym.endsWith("/") then None
    else if sym.endsWith("#") then
      val withoutHash = sym.stripSuffix("#")
      val innerIdx = withoutHash.lastIndexOf('#')
      if innerIdx >= 0 then Some(withoutHash.substring(0, innerIdx + 1))
      else packageRaw(withoutHash)
    else
      val idx = sym.lastIndexOf('#')
      if idx >= 0 then
        val rest = sym.stripSuffix(".").substring(idx + 1)
        val dotIdx = rest.lastIndexOf('.')
        if dotIdx >= 0 then Some(sym.substring(0, idx + 1) + rest.substring(0, dotIdx) + ".")
        else Some(sym.substring(0, idx + 1))
      else
        val stripped = sym.stripSuffix(".")
        val slashIdx = stripped.lastIndexOf('/')
        if slashIdx < 0 then None
        else
          val seg = stripped.substring(slashIdx + 1)
          val dotIdx = seg.lastIndexOf('.')
          if dotIdx >= 0 then Some(stripped.substring(0, slashIdx + 1) + seg.substring(0, dotIdx) + ".")
          else Some(stripped.substring(0, slashIdx + 1)) // top-level: the package

  /** Dot-form node id of a raw symbol (`com/example/a/Foo#` -> `com.example.a.Foo`, etc.). */
  private def dotFormId(sym: String): String =
    if sym.endsWith("/") then sym.stripSuffix("/").replace('/', '.')
    else if sym.endsWith("#") then typeId(sym)
    else memberId(sym)

  private def packageRaw(symNoTerminator: String): Option[String] =
    val slashIdx = symNoTerminator.lastIndexOf('/')
    if slashIdx >= 0 then Some(symNoTerminator.substring(0, slashIdx + 1)) else None

  /** Source URI relative to the workspace root; relative URIs kept as-is; absolute URIs outside the root fall back to the file name. */
  private def fileId(uri: String, root: java.nio.file.Path): String =
    val p = java.nio.file.Paths.get(uri)
    if p.isAbsolute && p.startsWith(root) then root.relativize(p).toString
    else if !p.isAbsolute then p.toString
    else p.getFileName.toString

  /** SemanticDB names synthetically introduced locals `local0`, `local1`, ... */
  private def isLocalSymbol(sym: String): Boolean =
    sym.startsWith("local") && sym.length > 5 && sym.drop(5).forall(_.isDigit)
