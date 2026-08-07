package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.codeps.graph.Filter

class FilterSpec extends munit.FunSuite:

  val ownPackages = Set(
    "com.example.modules.module1",
    "com.example.modules.module2",
    "com.example.util",
    "org.thirdparty"
  )
  val edges = Set(
    PackageEdge("com.example.modules.module1", "com.example.util"),
    PackageEdge("com.example.modules.module2", "com.example.modules.module1"),
    PackageEdge("com.example.modules.module2", "org.thirdparty"),
    PackageEdge("com.example.modules.module2", "java.lang"),
    PackageEdge("com.example.util", "java.lang")
  )
  val counts = Map(
    "com.example.modules.module1" -> PkgStats(2, 3),
    "com.example.modules.module2" -> PkgStats(4, 5),
    "com.example.util"            -> PkgStats(6, 7),
    "org.thirdparty"              -> PkgStats(8, 9)
  )

  test("include defines the universe; package matches itself and sub-packages") {
    val (universe, kept, c) = Filter(ownPackages, edges, counts, Seq("com.example.modules"), Nil)
    assertEquals(
      universe,
      Set("com.example.modules.module1", "com.example.modules.module2")
    )
    // edges kept only when both endpoints in universe
    assertEquals(kept, Set(PackageEdge("com.example.modules.module2", "com.example.modules.module1")))
    // counts kept only for surviving packages
    assertEquals(
      c,
      Map("com.example.modules.module1" -> PkgStats(2, 3), "com.example.modules.module2" -> PkgStats(4, 5))
    )
  }

  test("exclude removes from universe and wins over include") {
    val (universe, kept, _) = Filter(
      ownPackages,
      edges,
      counts,
      Seq("com.example.modules"),
      Seq("com.example.modules.module2")
    )
    assertEquals(universe, Set("com.example.modules.module1"))
    assertEquals(kept, Set.empty[PackageEdge])
  }

  test("self-edges are dropped") {
    val withLoop = edges + PackageEdge("com.example.util", "com.example.util")
    val (_, kept, _) = Filter(ownPackages, withLoop, counts, Seq("com.example.util"), Nil)
    assertEquals(kept, Set.empty[PackageEdge])
  }

  test("include pattern matching is prefix-based, not substring") {
    val (universe, _, _) = Filter(ownPackages, edges, counts, Seq("com.example"), Nil)
    assert(universe.contains("com.example.util"))
    assert(!universe.contains("org.thirdparty"))
  }
