package ba.sake.codeps.semanticdb

import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.codeps.graph.Aggregator
import ba.sake.codeps.model.{DeclarationSurface, DepsGraph, Edge, Node, NodeKind, SymbolReference}
import ba.sake.codeps.report.MetricsCalculator
import scala.meta.internal.semanticdb.{Access, ByNameType, ClassSignature, PrivateAccess, PrivateThisAccess, PrivateWithinAccess, ProtectedAccess, PublicAccess, Range, Signature, SymbolInformation, SymbolOccurrence, TextDocument, TextDocuments, Type, TypeRef, ValueSignature}

class SemanticDbParserSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  private def parseAll(): DepsGraph =
    val root = os.pwd.toNIO
    FixtureCompiler.semanticdbFiles.foldLeft(DepsGraph.empty) { case (deps, file) =>
      SemanticDbParser.parse(os.read.bytes(file), root) match
        case Right(d) => deps.merge(d)
        case Left(err) => fail(err)
    }.withoutDanglingEdges

  test("parses real compiled semanticdb files into granular nodes") {
    val deps = parseAll()
    val ids = deps.nodes.map(_.id)
    // packages
    assert(ids.contains("com.example.app"))
    assert(ids.contains("org.thirdparty"))
    // types (object Main, class Service1, ...)
    assert(ids.contains("com.example.app.Main"))
    assert(ids.contains("com.example.modules.module1.Service1"))
    assert(ids.contains("org.thirdparty.Ext"))
    // members
    assert(ids.contains("com.example.app.Main#main"))
    assert(ids.contains("com.example.util.Helper#help"))
    assert(ids.contains("org.thirdparty.Ext#name"))
    // files: workspace-relative paths, one per source file
    val files = deps.nodes.filter(_.kind == NodeKind.file).map(_.id)
    assertEquals(files.size, 8) // 7 original + Exposure.scala
    assert(files.exists(_.endsWith("/com/example/app/Main.scala")))
    assert(files.exists(_.endsWith("/org/thirdparty/Ext.scala")))
    assert(files.exists(_.endsWith("/com/example/util/HelperSpec.scala")))
    assert(files.exists(_.endsWith("/com/example/specs/OnlyTestsHereSpec.scala")))
    assert(files.exists(_.endsWith("/com/example/util/Exposure.scala")))
    // type nodes carry parent package and file
    assert(deps.nodes.contains(Node("com.example.util.Helper", NodeKind.`type`, Some("com.example.util"), Some(files.find(_.endsWith("Helper.scala")).get), isExposed = true, ports = 3.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1))))
  }

  test("edges are member-level and internal only after pruning") {
    val deps = parseAll()
    assert(deps.edges.contains(Edge("com.example.modules.module1.Service1#run", "com.example.util.Helper#help")))
    assert(deps.edges.contains(Edge("com.example.modules.module2.Service2#run", "com.example.modules.module1.Service1#run")))
    assert(deps.edges.contains(Edge("com.example.modules.module2.Service2#run", "org.thirdparty.Ext#name")))
    assert(deps.edges.contains(Edge("com.example.app.Main#main", "com.example.modules.module2.Service2#run")))
    // no edge leaves the node set (external refs pruned)
    val ids = deps.nodes.map(_.id)
    assert(deps.edges.forall(e => ids.contains(e.source) && ids.contains(e.target)))
    // package-declaration symbols must not create edges
    assert(!deps.edges.exists(_.source == "com.example.util"))
  }

  test("unpruned parse output contains external edges") {
    val root = os.pwd.toNIO
    val raw = FixtureCompiler.semanticdbFiles.foldLeft(DepsGraph.empty) { case (deps, file) =>
      deps.merge(SemanticDbParser.parse(os.read.bytes(file), root).toOption.get)
    }
    assert(raw.edges.exists(e => e.target.startsWith("scala.")))
  }

  test("corrupt bytes are reported as Left") {
    val result = SemanticDbParser.parse(Array[Byte](1, 2, 3, 4, 5), os.pwd.toNIO)
    assert(result.isLeft)
  }

  test("synthetic document: top-level and object members, object-anchored source") {
    val root = os.pwd.toNIO
    val file = "src/com/example/a/Top.scala"
    val doc = TextDocument(
      uri = file,
      symbols = Seq(
        SymbolInformation(symbol = "com/example/app/Main.", kind = SymbolInformation.Kind.OBJECT, displayName = "Main"),
        SymbolInformation(symbol = "com/example/app/Main.main().", kind = SymbolInformation.Kind.METHOD, displayName = "main"),
        SymbolInformation(symbol = "com/example/a/topLevelHelper.", kind = SymbolInformation.Kind.METHOD, displayName = "topLevelHelper"),
        SymbolInformation(symbol = "com/example/app/Deps#", kind = SymbolInformation.Kind.CLASS, displayName = "Deps")
      ),
      occurrences = Seq(
        SymbolOccurrence(range = Some(Range(1, 0, 1, 4)), symbol = "com/example/app/Main.", role = SymbolOccurrence.Role.DEFINITION),
        // reference in the object body before any member definition: nearest preceding anchor is the object itself
        SymbolOccurrence(range = Some(Range(2, 0, 2, 4)), symbol = "com/example/app/Deps#", role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(range = Some(Range(3, 0, 3, 4)), symbol = "com/example/app/Main.main().", role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(range = Some(Range(5, 0, 5, 14)), symbol = "com/example/a/topLevelHelper.", role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(range = Some(Range(7, 0, 7, 4)), symbol = "com/example/app/Deps#", role = SymbolOccurrence.Role.DEFINITION)
      )
    )
    val deps = SemanticDbParser.parse(TextDocuments(Seq(doc)).toByteArray, root).toOption.get.withoutDanglingEdges
    // top-level member: no '#' in the id, parent is the package
    assert(deps.nodes.contains(Node("com.example.a.topLevelHelper", NodeKind.member, Some("com.example.a"), Some(file), isExposed = true, ports = 1.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1))))
    // object member: dot of the object separator becomes '#', parent is the object
    assert(deps.nodes.contains(Node("com.example.app.Main#main", NodeKind.member, Some("com.example.app.Main"), Some(file), isExposed = true, ports = 1.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1))))
    // the object itself is a type node
    assert(deps.nodes.contains(Node("com.example.app.Main", NodeKind.`type`, Some("com.example.app"), Some(file), isExposed = true, ports = 3.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1))))
    // the reference anchored on the object resolves to the object's type node id, not a dangling '#' id
    assert(deps.nodes.contains(Node("com.example.app.Deps", NodeKind.`type`, Some("com.example.app"), Some(file), isExposed = true, ports = 3.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1))))
    assert(deps.edges.contains(Edge("com.example.app.Main", "com.example.app.Deps")))
  }

  test("class-scoped private symbols are collapsed into their nearest non-private ancestor") {
    val root = os.pwd.toNIO
    val file = "src/com/example/a/Priv.scala"
    val doc = TextDocument(
      uri = file,
      symbols = Seq(
        SymbolInformation(symbol = "com/example/a/Priv#", kind = SymbolInformation.Kind.CLASS, displayName = "Priv", access = Access.Empty),
        SymbolInformation(symbol = "com/example/a/Priv#pub().", kind = SymbolInformation.Kind.METHOD, displayName = "pub", access = PublicAccess()),
        SymbolInformation(symbol = "com/example/a/Priv#priv().", kind = SymbolInformation.Kind.METHOD, displayName = "priv", access = PrivateAccess()),
        SymbolInformation(symbol = "com/example/a/Priv#privThis().", kind = SymbolInformation.Kind.METHOD, displayName = "privThis", access = PrivateThisAccess()),
        SymbolInformation(symbol = "com/example/a/Priv#pkgPriv().", kind = SymbolInformation.Kind.METHOD, displayName = "pkgPriv", access = PrivateWithinAccess("com.example.a")),
        SymbolInformation(symbol = "com/example/a/Priv#prot().", kind = SymbolInformation.Kind.METHOD, displayName = "prot", access = ProtectedAccess()),
        SymbolInformation(symbol = "com/example/a/Hidden.", kind = SymbolInformation.Kind.OBJECT, displayName = "Hidden", access = PrivateAccess()),
        SymbolInformation(symbol = "com/example/b/Other#", kind = SymbolInformation.Kind.CLASS, displayName = "Other", access = Access.Empty)
      ),
      occurrences = Seq(
        SymbolOccurrence(range = Some(Range(1, 0, 1, 4)), symbol = "com/example/a/Priv#", role = SymbolOccurrence.Role.DEFINITION),
        // pub references Other: source stays pub
        SymbolOccurrence(range = Some(Range(2, 0, 2, 3)), symbol = "com/example/a/Priv#pub().", role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(range = Some(Range(3, 0, 3, 3)), symbol = "com/example/b/Other#", role = SymbolOccurrence.Role.REFERENCE),
        // priv references Other: source collapses to the enclosing type Priv
        SymbolOccurrence(range = Some(Range(4, 0, 4, 3)), symbol = "com/example/a/Priv#priv().", role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(range = Some(Range(5, 0, 5, 3)), symbol = "com/example/b/Other#", role = SymbolOccurrence.Role.REFERENCE),
        // privThis references Other: source collapses to Priv
        SymbolOccurrence(range = Some(Range(6, 0, 6, 3)), symbol = "com/example/a/Priv#privThis().", role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(range = Some(Range(7, 0, 7, 3)), symbol = "com/example/b/Other#", role = SymbolOccurrence.Role.REFERENCE),
        // pkgPriv references Other: source stays pkgPriv (kept as a node)
        SymbolOccurrence(range = Some(Range(8, 0, 8, 3)), symbol = "com/example/a/Priv#pkgPriv().", role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(range = Some(Range(9, 0, 9, 3)), symbol = "com/example/b/Other#", role = SymbolOccurrence.Role.REFERENCE),
        // prot references Other: source stays prot (kept as a node)
        SymbolOccurrence(range = Some(Range(10, 0, 10, 3)), symbol = "com/example/a/Priv#prot().", role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(range = Some(Range(11, 0, 11, 3)), symbol = "com/example/b/Other#", role = SymbolOccurrence.Role.REFERENCE),
        // pub references the private member priv: target collapses to the type Priv
        SymbolOccurrence(range = Some(Range(12, 0, 12, 3)), symbol = "com/example/a/Priv#priv().", role = SymbolOccurrence.Role.REFERENCE),
        // pub references the private top-level object Hidden: dropped (file-scoped, no cross-file info)
        SymbolOccurrence(range = Some(Range(13, 0, 13, 3)), symbol = "com/example/a/Hidden.", role = SymbolOccurrence.Role.REFERENCE),
        // Other definition at the end so its node exists and edges stay internal
        SymbolOccurrence(range = Some(Range(14, 0, 14, 3)), symbol = "com/example/b/Other#", role = SymbolOccurrence.Role.DEFINITION)
      )
    )
    val deps = SemanticDbParser.parse(TextDocuments(Seq(doc)).toByteArray, root).toOption.get.withoutDanglingEdges
    val ids = deps.nodes.map(_.id)
    // class-scoped private symbols are not nodes; private[pkg] and protected are kept
    assert(!ids.contains("com.example.a.Priv#priv"))
    assert(!ids.contains("com.example.a.Priv#privThis"))
    assert(!ids.contains("com.example.a.Hidden"))
    assert(ids.contains("com.example.a.Priv#pub"))
    assert(ids.contains("com.example.a.Priv#pkgPriv"))
    assert(ids.contains("com.example.a.Priv#prot"))
    // reference inside priv (line 5) is attributed to the enclosing type
    assert(deps.edges.contains(Edge("com.example.a.Priv", "com.example.b.Other")))
    // reference inside privThis (line 7) too
    assert(deps.edges.contains(Edge("com.example.a.Priv", "com.example.b.Other")))
    // references inside kept members stay on the member
    assert(deps.edges.contains(Edge("com.example.a.Priv#pkgPriv", "com.example.b.Other")))
    assert(deps.edges.contains(Edge("com.example.a.Priv#prot", "com.example.b.Other")))
    // reference to a class-private member collapses to the declaring type
    assert(deps.edges.contains(Edge("com.example.a.Priv#prot", "com.example.a.Priv")))
    // reference to the private top-level object produces no edge
    assert(!deps.edges.exists(_.target == "com.example.a.Hidden"))
  }

  test("exposure fields: public/private access, sealed, given, var, mutable collections") {
    val root = os.pwd.toNIO
    val file = "src/com/example/a/Exp.scala"
    def sym(symbol: String, kind: SymbolInformation.Kind, access: Access = Access.Empty,
            properties: Int = 0, signature: Signature = Signature.Empty, displayName: String = ""): SymbolInformation =
      SymbolInformation(symbol = symbol, kind = kind, displayName = displayName, access = access,
        properties = properties, signature = signature)
    val doc = TextDocument(
      uri = file,
      symbols = Seq(
        sym("com/example/a/Exp#", SymbolInformation.Kind.CLASS),
        sym("com/example/a/Exp#plain().", SymbolInformation.Kind.METHOD, displayName = "plain"),
        sym("com/example/a/Exp#hidden().", SymbolInformation.Kind.METHOD, access = PrivateAccess(), displayName = "hidden"),
        sym("com/example/a/Exp#hiddenCounter.", SymbolInformation.Kind.FIELD, access = PrivateAccess(),
          properties = SymbolInformation.Property.VAR.value, displayName = "hiddenCounter"),
        sym("com/example/a/Exp#pkgPriv().", SymbolInformation.Kind.METHOD,
          access = PrivateWithinAccess("com.example.a"), displayName = "pkgPriv"),
        sym("com/example/a/Exp#prot().", SymbolInformation.Kind.METHOD, access = ProtectedAccess(), displayName = "prot"),
        sym("com/example/a/Sealed#", SymbolInformation.Kind.TRAIT, properties = SymbolInformation.Property.SEALED.value),
        sym("com/example/a/Sealed#m().", SymbolInformation.Kind.METHOD, displayName = "m"),
        sym("com/example/a/Impl#", SymbolInformation.Kind.CLASS,
          signature = ClassSignature(None, Seq(TypeRef(Type.Empty, "com/example/a/Sealed#", Nil)), Type.Empty, None)),
        sym("com/example/a/Exp#counter.", SymbolInformation.Kind.FIELD, properties = SymbolInformation.Property.VAR.value, displayName = "counter"),
        sym("com/example/a/Exp#counter_=().", SymbolInformation.Kind.METHOD, displayName = "counter_="),
        sym("com/example/a/Exp#buffer.", SymbolInformation.Kind.FIELD, displayName = "buffer",
          signature = ValueSignature(TypeRef(Type.Empty, "scala/collection/mutable/ArrayBuffer#", Nil))),
        sym("com/example/a/Exp#fresh().", SymbolInformation.Kind.METHOD, displayName = "fresh",
          signature = ValueSignature(ByNameType(TypeRef(Type.Empty, "scala/collection/mutable/Buffer#", Nil)))),
        sym("com/example/a/Exp#givenOrd.", SymbolInformation.Kind.METHOD,
          properties = SymbolInformation.Property.IMPLICIT.value | SymbolInformation.Property.VAR.value, displayName = "givenOrd")
      ),
      occurrences = Seq.empty
    )
    val nodes = SemanticDbParser.parse(TextDocuments(Seq(doc)).toByteArray, root).toOption.get.nodes
    def node(id: String) = nodes.find(_.id == id).get
    assertEquals(node("com.example.a.Exp"), Node("com.example.a.Exp", NodeKind.`type`, Some("com.example.a"), Some(file), isExposed = true, ports = 3.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1)))
    assertEquals(node("com.example.a.Exp#plain"), Node("com.example.a.Exp#plain", NodeKind.member, Some("com.example.a.Exp"), Some(file), isExposed = true, ports = 1.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1)))
    assert(!nodes.exists(_.id == "com.example.a.Exp#hidden")) // class-private: dropped, as before
    assert(!nodes.exists(_.id == "com.example.a.Exp#hiddenCounter")) // class-private mutable: dropped, but counted on file
    assertEquals(node("com.example.a.Exp#prot").isExposed, false) // protected: not exposed
    assertEquals(node("com.example.a.Exp#prot").declarationSurface, DeclarationSurface(`protected` = 1))
    assertEquals(node("com.example.a.Exp#pkgPriv").declarationSurface, DeclarationSurface(packageRestricted = 1))
    assertEquals(node("com.example.a.Sealed"), Node("com.example.a.Sealed", NodeKind.`type`, Some("com.example.a"), Some(file), isExposed = true, ports = 0.5, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1)))
    assertEquals(node("com.example.a.Sealed#m"), Node("com.example.a.Sealed#m", NodeKind.member, Some("com.example.a.Sealed"), Some(file), isExposed = true, ports = 0.5, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1))) // in sealed hierarchy
    assertEquals(node("com.example.a.Impl").ports, 0.5) // extends a sealed trait: part of the sealed hierarchy
    assertEquals(node("com.example.a.Impl").declarationSurface, DeclarationSurface(public = 1))
    assertEquals(node("com.example.a.Exp#counter").mutPorts, 1.0) // var
    assertEquals(node("com.example.a.Exp#counter_=").isExposed, false) // var setter: accessor, not surface
    assertEquals(node("com.example.a.Exp#buffer").mutPorts, 1.0) // mutable collection val
    assertEquals(node("com.example.a.Exp#fresh").mutPorts, 1.0) // getter returning mutable (by-name signature)
    assertEquals(node("com.example.a.Exp#givenOrd"), Node("com.example.a.Exp#givenOrd", NodeKind.member, Some("com.example.a.Exp"), Some(file), isExposed = true, ports = 1.0, mutPorts = 0.0, declarationSurface = DeclarationSurface(public = 1))) // given: flat +1, and never mut (compiler marks givens VAR spuriously)
    assertEquals(nodes.find(_.id == file).get.declarationSurface, DeclarationSurface(privateMembers = 2, privateMutable = 1))
  }

  test("real fixture: Exposure.scala resolves exposure on compiled output") {
    val root = os.pwd.toNIO
    val deps = FixtureCompiler.semanticdbFiles.foldLeft(DepsGraph.empty) { case (d, f) =>
      d.merge(SemanticDbParser.parse(os.read.bytes(f), root).toOption.get)
    }
    val nodes = deps.nodes.map(n => n.id -> n).toMap
    assertEquals(nodes("com.example.util.SealedBase").ports, 0.5) // sealed trait
    assertEquals(nodes("com.example.util.SealedBase#sealedMethod").ports, 0.5) // member of sealed hierarchy
    assertEquals(nodes("com.example.util.SealedImpl").ports, 0.5) // extends the sealed trait
    assertEquals(nodes("com.example.util.Exposure#counter").mutPorts, 1.0) // var
    assertEquals(nodes("com.example.util.Exposure#`counter_=`").isExposed, false) // var setter: not surface
    assertEquals(nodes("com.example.util.Exposure#buffer").mutPorts, 1.0) // mutable val
    assertEquals(nodes("com.example.util.Exposure#fresh").mutPorts, 1.0) // mutable getter (by-name signature)
    assertEquals(nodes("com.example.util.Exposure#plain").ports, 1.0)
    assertEquals(nodes("com.example.util.Exposure#pkgPriv").isExposed, false) // private[util]
    assertEquals(nodes("com.example.util.Exposure#prot").isExposed, false) // protected
    assert(!nodes.contains("com.example.util.Exposure#hidden")) // private: dropped
    assertEquals(nodes("com.example.util.Exposure").ports, 3.0) // the class itself
    assertEquals(nodes("com.example.util.Exposure#intOrdering").ports, 1.0) // given: flat +1
    assertEquals(nodes("com.example.util.Exposure#intOrdering").mutPorts, 0.0) // givens are not mutable state
    assertEquals(nodes("com.example.util.Exposure#stringConv").ports, 1.0) // implicit def
  }

  test("public symbol references retain stable targets and duplicate occurrences") {
    val root = os.pwd.toNIO
    val apiFile = "src/com/example/refs/Api.scala"
    val consumerFile = "src/com/example/refs/Consumer.scala"
    val api = TextDocument(
      uri = apiFile,
      symbols = Seq(
        SymbolInformation(symbol = "com/example/refs/Api#", kind = SymbolInformation.Kind.CLASS, displayName = "Api"),
        SymbolInformation(symbol = "com/example/refs/Api#used().", kind = SymbolInformation.Kind.METHOD, displayName = "used"),
        SymbolInformation(symbol = "com/example/refs/Api#unused().", kind = SymbolInformation.Kind.METHOD, displayName = "unused")
      ),
      occurrences = Seq(
        SymbolOccurrence(Some(Range(1, 0, 1, 3)), "com/example/refs/Api#", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(2, 0, 2, 4)), "com/example/refs/Api#used().", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(3, 0, 3, 6)), "com/example/refs/Api#unused().", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(4, 0, 4, 4)), "com/example/refs/Api#used().", SymbolOccurrence.Role.REFERENCE)
      )
    )
    val consumer = TextDocument(
      uri = consumerFile,
      symbols = Seq(SymbolInformation(symbol = "com/example/refs/Consumer#", kind = SymbolInformation.Kind.CLASS, displayName = "Consumer")),
      occurrences = Seq(
        SymbolOccurrence(Some(Range(1, 0, 1, 8)), "com/example/refs/Consumer#", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(2, 0, 2, 4)), "com/example/refs/Api#used().", SymbolOccurrence.Role.REFERENCE)
      )
    )
    val bytes = TextDocuments(Seq(api, consumer)).toByteArray
    val deps = SemanticDbParser.parse(bytes, root).toOption.get.withoutDanglingEdges

    assert(deps.nodes.exists(_.id == "com.example.refs.Api#used"))
    assertEquals(
      deps.symbolReferences.get.filter(_.targetSymbol == "com.example.refs.Api#used"),
      Seq(SymbolReference(apiFile, "com.example.refs.Api#used"), SymbolReference(consumerFile, "com.example.refs.Api#used"))
    )
    assertEquals(deps.symbolReferences.get.count(_.targetSymbol == "com.example.refs.Api#unused"), 0)
  }

  test("public declaration index excludes compiler-generated symbols but keeps source identifiers") {
    val root = os.pwd.toNIO
    val file = "src/com/example/synthetic/Api.scala"
    val doc = TextDocument(
      uri = file,
      symbols = Seq(
        SymbolInformation(symbol = "com/example/synthetic/Api#", kind = SymbolInformation.Kind.CLASS, displayName = "Api"),
        // Scala 3 currently emits generated Product accessors without the
        // SYNTHETIC property and without a definition occurrence.
        SymbolInformation(symbol = "com/example/synthetic/Config#App#_1().", kind = SymbolInformation.Kind.METHOD, displayName = "_1"),
        SymbolInformation(symbol = "[_].", kind = SymbolInformation.Kind.METHOD, displayName = "_"),
        // A producer that marks a generated declaration explicitly must also be
        // excluded even if it happens to carry a definition occurrence.
        SymbolInformation(
          symbol = "com/example/synthetic/Api#generated().",
          kind = SymbolInformation.Kind.METHOD,
          displayName = "generated",
          properties = SymbolInformation.Property.SYNTHETIC.value
        ),
        // `_1` is a legal source identifier; a definition occurrence keeps it
        // in the public API index.
        SymbolInformation(symbol = "com/example/synthetic/Api#_1().", kind = SymbolInformation.Kind.METHOD, displayName = "_1"),
        SymbolInformation(symbol = "com/example/synthetic/Api#user().", kind = SymbolInformation.Kind.METHOD, displayName = "user")
      ),
      occurrences = Seq(
        SymbolOccurrence(Some(Range(0, 0, 0, 1)), "com/example/synthetic/", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(1, 0, 1, 3)), "com/example/synthetic/Api#", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(2, 0, 2, 8)), "com/example/synthetic/Api#generated().", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(3, 0, 3, 8)), "com/example/synthetic/Api#_1().", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(4, 0, 4, 4)), "com/example/synthetic/Api#user().", SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(Some(Range(5, 0, 5, 4)), "com/example/synthetic/Config#App#_1().", SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(Some(Range(6, 0, 6, 4)), "[_].", SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(Some(Range(7, 0, 7, 4)), "com/example/synthetic/Api#_1().", SymbolOccurrence.Role.REFERENCE)
      )
    )

    val deps = SemanticDbParser.parse(TextDocuments(Seq(doc)).toByteArray, root).toOption.get.withoutDanglingEdges
    val declarations = deps.declaredPublicSymbols.get
    assertEquals(
      declarations,
      Map(
        "com.example.synthetic.Api" -> file,
        "com.example.synthetic.Api#_1" -> file,
        "com.example.synthetic.Api#user" -> file
      )
    )
    assert(!declarations.contains("com.example.synthetic.Config#App#_1"))
    assert(!declarations.contains("[_]"))
    assert(!declarations.contains("com.example.synthetic.Api#generated"))
    assertEquals(
      deps.symbolReferences.get,
      Seq(SymbolReference(file, "com.example.synthetic.Api#_1"))
    )

    val report = MetricsCalculator.run(Aggregator.fileLevel(deps), MetricsCalculator.Scope.Packages).toOption.get
    assert(report.publicSymbols.get.exists(_.symbol == "com.example.synthetic.Api#_1"))
    assert(report.findings.exists(_.id == "unusedPublicSymbol:com.example.synthetic.Api#user"))
    assert(!report.findings.exists(_.id.contains("Config#App#_1")))
    assert(!report.findings.exists(_.id == "unusedPublicSymbol:[_]"))
  }
