package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.codeps.graph.Collapser

class CollapserSpec extends munit.FunSuite:

  test("no rules leaves graph unchanged") {
    val nodes = Set("a.b", "a.c")
    val edges = Set(PackageEdge("a.b", "a.c"))
    assertEquals(Collapser.collapse(nodes, edges, Nil), (nodes, edges))
  }

  test("wild merges nodes and re-derives edges") {
    val nodes = Set("com.example.foo", "com.example.bar.baz", "org.other")
    val edges = Set(
      PackageEdge("com.example.foo", "com.example.bar.baz"),
      PackageEdge("org.other", "com.example.foo")
    )
    val (n, e) = Collapser.collapse(nodes, edges, Seq(CollapseRule.Wild("com.example")))
    assertEquals(n, Set("com.example", "org.other"))
    assertEquals(e, Set(PackageEdge("org.other", "com.example"))) // inner loop dropped
  }

  test("single level keeps one level below prefix") {
    val nodes = Set("org.lib.foo.bar", "org.lib.baz.qux", "org.lib.alone")
    val edges = Set(PackageEdge("org.lib.foo.bar", "org.lib.baz.qux"))
    val (n, e) = Collapser.collapse(nodes, edges, Seq(CollapseRule.SingleLevel("org.lib")))
    assertEquals(n, Set("org.lib.foo", "org.lib.baz", "org.lib.alone"))
    assertEquals(e, Set(PackageEdge("org.lib.foo", "org.lib.baz")))
  }

  test("longest prefix wins, first rule wins ties") {
    val nodes = Set("com.example.modules.m1", "com.example.other")
    val edges = Set(PackageEdge("com.example.modules.m1", "com.example.other"))
    val rules = Seq(
      CollapseRule.Wild("com.example"),
      CollapseRule.Wild("com.example.modules")
    )
    val (n, e) = Collapser.collapse(nodes, edges, rules)
    assertEquals(n, Set("com.example.modules", "com.example"))
    assertEquals(e, Set(PackageEdge("com.example.modules", "com.example")))
  }

  test("collapse can create loops which are dropped") {
    val nodes = Set("a.b.c", "a.b.x")
    val edges = Set(PackageEdge("a.b.c", "a.b.x"))
    val (n, e) = Collapser.collapse(nodes, edges, Seq(CollapseRule.Wild("a.b")))
    assertEquals(n, Set("a.b"))
    assertEquals(e, Set.empty[PackageEdge])
  }
