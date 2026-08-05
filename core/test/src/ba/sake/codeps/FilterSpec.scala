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

  test("include defines the universe; package matches itself and sub-packages") {
    val (universe, kept) = Filter(ownPackages, edges, Seq("com.example.modules"), Nil)
    assertEquals(
      universe,
      Set("com.example.modules.module1", "com.example.modules.module2")
    )
    // edges kept only when both endpoints in universe
    assertEquals(kept, Set(PackageEdge("com.example.modules.module2", "com.example.modules.module1")))
  }

  test("exclude removes from universe and wins over include") {
    val (universe, kept) = Filter(
      ownPackages,
      edges,
      Seq("com.example.modules"),
      Seq("com.example.modules.module2")
    )
    assertEquals(universe, Set("com.example.modules.module1"))
    assertEquals(kept, Set.empty[PackageEdge])
  }

  test("self-edges are dropped") {
    val withLoop = edges + PackageEdge("com.example.util", "com.example.util")
    val (_, kept) = Filter(ownPackages, withLoop, Seq("com.example.util"), Nil)
    assertEquals(kept, Set.empty[PackageEdge])
  }

  test("include pattern matching is prefix-based, not substring") {
    val (universe, _) = Filter(ownPackages, edges, Seq("com.example"), Nil)
    assert(universe.contains("com.example.util"))
    assert(!universe.contains("org.thirdparty"))
  }
