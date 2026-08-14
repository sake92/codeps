package ba.sake.codeps.jdeps

import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.codeps.model.{DepsGraph, Edge, Node, NodeKind}

class JdepsParserSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  val sample = """classes -> java.base
                 |classes -> not found
                 |   com.example.app.Main$                        -> com.example.modules.module2.Service2   classes
                 |   com.example.app.Main$                        -> java.lang.Object                        java.base
                 |   com.example.modules.module1.Service1         -> com.example.util.Helper                 classes
                 |   com.example.modules.module1.Service1         -> java.lang.Object                        java.base
                 |   com.example.modules.module2.Service2         -> com.example.modules.module1.Service1    classes
                 |   com.example.modules.module2.Service2         -> org.thirdparty.Ext$                     classes
                 |   com.example.modules.module2.Service2         -> java.lang.String                        java.base
                 |   com.example.util.Helper                      -> java.lang.String                        java.base
                 |   org.thirdparty.Ext$                          -> java.lang.String                        java.base
                 |""".stripMargin

  test("parses class-level detail lines, skips summary lines and externals") {
    val deps = JdepsParser.parse(sample)
    // Scala object classes lose their trailing `$`
    assert(deps.nodes.contains(Node("com.example.app.Main", NodeKind.`type`, Some("com.example.app"))))
    assert(deps.nodes.contains(Node("org.thirdparty.Ext", NodeKind.`type`, Some("org.thirdparty"))))
    // package nodes come from the FQCN prefixes
    assert(deps.nodes.contains(Node("com.example.modules.module1", NodeKind.`package`)))
    // internal class edges kept
    assert(deps.edges.contains(Edge("com.example.modules.module2.Service2", "org.thirdparty.Ext")))
    assert(deps.edges.contains(Edge("com.example.modules.module1.Service1", "com.example.util.Helper")))
    // external (java.*) targets dropped; summary lines ignored
    assert(!deps.edges.exists(_.target.startsWith("java.")))
    assert(!deps.nodes.exists(_.id == "classes"))
  }

  test("inner classes map `$` to `#` with parent chains") {
    val sample = """classes -> java.base
                   |   com.example.app.Outer$Inner              -> com.example.app.Outer        classes
                   |   com.example.app.Outer                    -> java.lang.Object             java.base
                   |   com.example.app.Outer$Inner$             -> java.lang.Object             java.base
                   |""".stripMargin
    val deps = JdepsParser.parse(sample)
    assert(deps.nodes.contains(Node("com.example.app.Outer#Inner", NodeKind.`type`, Some("com.example.app.Outer"))))
    assert(deps.nodes.contains(Node("com.example.app.Outer", NodeKind.`type`, Some("com.example.app"))))
    // the Outer$Inner$ source maps to the same node id; its java.lang.Object target is dropped
    assert(deps.edges.contains(Edge("com.example.app.Outer#Inner", "com.example.app.Outer")))
    assertEquals(deps.edges.size, 1)
  }

  test("parses real jdeps output of compiled fixtures") {
    val deps = JdepsParser.parse(os.read(FixtureCompiler.jdepsFile))
    assert(deps.nodes.contains(Node("com.example.modules.module2.Service2", NodeKind.`type`, Some("com.example.modules.module2"))))
    assert(deps.edges.contains(Edge("com.example.modules.module2.Service2", "org.thirdparty.Ext")))
    // self-edges are deliberately kept (Main -> Main$, Ext -> Ext$ collapse); dropped downstream
    assert(deps.edges.contains(Edge("com.example.app.Main", "com.example.app.Main")))
    assert(deps.edges.contains(Edge("org.thirdparty.Ext", "org.thirdparty.Ext")))
  }

  test("malformed lines are skipped") {
    val deps = JdepsParser.parse("garbage line\nnot an arrow\n   a.b -> \n   -> b.c\n")
    assertEquals(deps, DepsGraph.empty)
  }
