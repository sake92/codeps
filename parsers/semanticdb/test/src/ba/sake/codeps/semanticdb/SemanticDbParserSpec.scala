package ba.sake.codeps.semanticdb

import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.codeps.model.PackageEdge

class SemanticDbParserSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  test("parses real compiled semanticdb files") {
    val (own, edges) = FixtureCompiler.semanticdbFiles.foldLeft((Set.empty[String], Set.empty[PackageEdge])) {
      case ((own, edges), file) =>
        SemanticDbParser.parse(os.read.bytes(file)) match
          case Right((o, e)) => (own ++ o, edges ++ e)
          case Left(err)     => fail(err)
    }
    assertEquals(
      own,
      Set("com.example.app", "com.example.modules.module1", "com.example.modules.module2", "com.example.util", "org.thirdparty")
    )
    assert(edges.contains(PackageEdge("com.example.modules.module1", "com.example.util")))
    assert(edges.contains(PackageEdge("com.example.modules.module2", "com.example.modules.module1")))
    assert(edges.contains(PackageEdge("com.example.modules.module2", "org.thirdparty")))
    assert(edges.contains(PackageEdge("com.example.app", "com.example.modules.module2")))
    // external references are emitted as edges too (filtered later by --include)
    assert(edges.exists(e => e.source == "com.example.util" && e.target == "scala"))
    // package-declaration symbols ("com/example/util/") must not create edges
    assert(!edges.exists(_.target == "com.example"))
  }

  test("corrupt bytes are reported as Left") {
    val result = SemanticDbParser.parse(Array[Byte](1, 2, 3, 4, 5))
    assert(result.isLeft)
  }
