package ba.sake.codeps.semanticdb

import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.codeps.model.{PackageEdge, PkgStats}

class SemanticDbParserSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  private def parseAll(): (Set[String], Set[PackageEdge], Map[String, PkgStats]) =
    FixtureCompiler.semanticdbFiles.foldLeft((Set.empty[String], Set.empty[PackageEdge], Map.empty[String, PkgStats])) {
      case ((own, edges, counts), file) =>
        SemanticDbParser.parse(os.read.bytes(file)) match
          case Right((o, e, c)) =>
            val merged = c.foldLeft(counts) { case (acc, (k, v)) =>
              acc.get(k) match
                case Some(prev) => acc.updated(k, prev + v)
                case None       => acc + (k -> v)
            }
            (own ++ o, edges ++ e, merged)
          case Left(err) => fail(err)
    }

  test("parses real compiled semanticdb files") {
    val (own, edges, _) = parseAll()
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

  test("counts one file and its class-like symbols per package") {
    val (_, _, counts) = parseAll()
    assertEquals(counts("com.example.util"), PkgStats(1, 1)) // Helper.scala / class Helper
    assertEquals(counts("com.example.modules.module1"), PkgStats(1, 1)) // Service1.scala / class Service1
    assertEquals(counts("com.example.app"), PkgStats(1, 1)) // Main.scala / object Main
    assertEquals(counts("org.thirdparty"), PkgStats(1, 1)) // Ext.scala / object Ext
  }

  test("corrupt bytes are reported as Left") {
    val result = SemanticDbParser.parse(Array[Byte](1, 2, 3, 4, 5))
    assert(result.isLeft)
  }
