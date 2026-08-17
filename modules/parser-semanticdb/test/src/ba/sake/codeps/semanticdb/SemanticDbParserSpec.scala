package ba.sake.codeps.semanticdb

import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.codeps.model.{DepsGraph, Edge, Node, NodeKind}
import scala.meta.internal.semanticdb.{Access, PrivateAccess, PrivateThisAccess, PrivateWithinAccess, ProtectedAccess, PublicAccess, Range, SymbolInformation, SymbolOccurrence, TextDocument, TextDocuments}

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
    assertEquals(files.size, 5)
    assert(files.exists(_.endsWith("/com/example/app/Main.scala")))
    assert(files.exists(_.endsWith("/org/thirdparty/Ext.scala")))
    // type nodes carry parent package and file
    assert(deps.nodes.contains(Node("com.example.util.Helper", NodeKind.`type`, Some("com.example.util"), Some(files.find(_.endsWith("Helper.scala")).get))))
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
    assert(deps.nodes.contains(Node("com.example.a.topLevelHelper", NodeKind.member, Some("com.example.a"), Some(file))))
    // object member: dot of the object separator becomes '#', parent is the object
    assert(deps.nodes.contains(Node("com.example.app.Main#main", NodeKind.member, Some("com.example.app.Main"), Some(file))))
    // the object itself is a type node
    assert(deps.nodes.contains(Node("com.example.app.Main", NodeKind.`type`, Some("com.example.app"), Some(file))))
    // the reference anchored on the object resolves to the object's type node id, not a dangling '#' id
    assert(deps.nodes.contains(Node("com.example.app.Deps", NodeKind.`type`, Some("com.example.app"), Some(file))))
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
