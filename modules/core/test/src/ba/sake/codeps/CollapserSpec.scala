package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.codeps.graph.Collapser

class CollapserSpec extends munit.FunSuite:

  test("no rules leaves graph unchanged") {
    val nodes = Set("a.b", "a.c")
    val edges = Set(Edge("a.b", "a.c"))
    assertEquals(Collapser.collapse(nodes, edges, Nil), (nodes, edges))
  }

  test("wild merges nodes and re-derives edges") {
    val nodes = Set("com.example.foo", "com.example.bar.baz", "org.other")
    val edges = Set(
      Edge("com.example.foo", "com.example.bar.baz"),
      Edge("org.other", "com.example.foo")
    )
    val (n, e) = Collapser.collapse(nodes, edges, Seq(CollapseRule.Wild("com.example")))
    assertEquals(n, Set("com.example", "org.other"))
    assertEquals(e, Set(Edge("org.other", "com.example"))) // inner loop dropped
  }

  test("single level keeps one level below prefix") {
    val nodes = Set("org.lib.foo.bar", "org.lib.baz.qux", "org.lib.alone")
    val edges = Set(Edge("org.lib.foo.bar", "org.lib.baz.qux"))
    val (n, e) = Collapser.collapse(nodes, edges, Seq(CollapseRule.SingleLevel("org.lib")))
    assertEquals(n, Set("org.lib.foo", "org.lib.baz", "org.lib.alone"))
    assertEquals(e, Set(Edge("org.lib.foo", "org.lib.baz")))
  }

  test("longest prefix wins, first rule wins ties") {
    val nodes = Set("com.example.modules.m1", "com.example.other")
    val edges = Set(Edge("com.example.modules.m1", "com.example.other"))
    val rules = Seq(
      CollapseRule.Wild("com.example"),
      CollapseRule.Wild("com.example.modules")
    )
    val (n, e) = Collapser.collapse(nodes, edges, rules)
    assertEquals(n, Set("com.example.modules", "com.example"))
    assertEquals(e, Set(Edge("com.example.modules", "com.example")))
  }

  test("edges collapsing onto the same pair are merged with summed weights") {
    val nodes = Set("com.example.modules.m1", "com.example.modules.m2", "com.example.other")
    val edges = Set(
      Edge("com.example.modules.m1", "com.example.other"),
      Edge("com.example.modules.m2", "com.example.other"),
      Edge("com.example.modules.m1", "com.example.modules.m2") // becomes a loop
    )
    val (n, e) = Collapser.collapse(nodes, edges, Seq(CollapseRule.Wild("com.example.modules")))
    assertEquals(n, Set("com.example.modules", "com.example.other"))
    assertEquals(e, Set(Edge("com.example.modules", "com.example.other", 2)))
  }

  test("collapse can create loops which are dropped") {
    val nodes = Set("a.b.c", "a.b.x")
    val edges = Set(Edge("a.b.c", "a.b.x"))
    val (n, e) = Collapser.collapse(nodes, edges, Seq(CollapseRule.Wild("a.b")))
    assertEquals(n, Set("a.b"))
    assertEquals(e, Set.empty[Edge])
  }
