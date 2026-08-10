package ba.sake.codeps.jdeps

import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.codeps.model.{PackageEdge, PkgStats}

class JdepsParserSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  val sample = """classes -> java.base
                 |classes -> not found
                 |   com.example.modules.module1                        -> com.example.util                                   classes
                 |   com.example.modules.module1                        -> java.lang                                          java.base
                 |   com.example.modules.module2                        -> com.example.modules.module1                        classes
                 |   com.example.modules.module2                        -> java.lang                                          java.base
                 |   com.example.modules.module2                        -> scala                                              not found
                 |   com.example.util                                   -> java.lang                                          java.base
                 |
                 |""".stripMargin

  test("parses indented package lines, skips summary lines") {
    val deps = JdepsParser.parse(sample)
    assertEquals(
      deps.own,
      Set("com.example.modules.module1", "com.example.modules.module2", "com.example.util")
    )
    assert(deps.edges.contains(PackageEdge("com.example.modules.module1", "com.example.util")))
    assert(deps.edges.contains(PackageEdge("com.example.modules.module2", "com.example.modules.module1")))
    assert(!deps.edges.exists(e => e.source == "classes"))
    assertEquals(deps.stats, Map.empty[String, PkgStats])
  }

  test("parses real jdeps output of compiled fixtures") {
    val deps = JdepsParser.parse(os.read(FixtureCompiler.jdepsFile))
    assert(deps.own.contains("com.example.modules.module2"))
    assert(deps.edges.contains(PackageEdge("com.example.modules.module2", "org.thirdparty")))
    assert(!deps.edges.exists(e => e.source == "classes"))
  }

  test("malformed lines are skipped") {
    val deps = JdepsParser.parse("garbage line\nnot an arrow\n   a.b -> \n   -> b.c\n")
    assertEquals(deps.own, Set.empty[String])
    assertEquals(deps.edges, Set.empty[PackageEdge])
  }
