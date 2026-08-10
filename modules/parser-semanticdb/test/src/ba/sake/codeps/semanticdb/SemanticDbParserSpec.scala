package ba.sake.codeps.semanticdb

import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.codeps.model.{PackageDeps, PackageEdge, PkgStats}

class SemanticDbParserSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  private def parseAll(): PackageDeps =
    FixtureCompiler.semanticdbFiles.foldLeft(PackageDeps.empty) { case (deps, file) =>
      SemanticDbParser.parse(os.read.bytes(file)) match
        case Right(d) => deps.merge(d)
        case Left(err) => fail(err)
    }

  test("parses real compiled semanticdb files") {
    val deps = parseAll()
    assertEquals(
      deps.own,
      Set("com.example.app", "com.example.modules.module1", "com.example.modules.module2", "com.example.util", "org.thirdparty")
    )
    assert(deps.edges.contains(PackageEdge("com.example.modules.module1", "com.example.util")))
    assert(deps.edges.contains(PackageEdge("com.example.modules.module2", "com.example.modules.module1")))
    assert(deps.edges.contains(PackageEdge("com.example.modules.module2", "org.thirdparty")))
    assert(deps.edges.contains(PackageEdge("com.example.app", "com.example.modules.module2")))
    // external references are emitted as edges too (filtered later by --include)
    assert(deps.edges.exists(e => e.source == "com.example.util" && e.target == "scala"))
    // package-declaration symbols ("com/example/util/") must not create edges
    assert(!deps.edges.exists(_.target == "com.example"))
  }

  test("counts one file and its class-like symbols per package") {
    val counts = parseAll().stats
    assertEquals(counts("com.example.util"), PkgStats(1, 1)) // Helper.scala / class Helper
    assertEquals(counts("com.example.modules.module1"), PkgStats(1, 1)) // Service1.scala / class Service1
    assertEquals(counts("com.example.app"), PkgStats(1, 1)) // Main.scala / object Main
    assertEquals(counts("org.thirdparty"), PkgStats(1, 1)) // Ext.scala / object Ext
  }

  test("corrupt bytes are reported as Left") {
    val result = SemanticDbParser.parse(Array[Byte](1, 2, 3, 4, 5))
    assert(result.isLeft)
  }
