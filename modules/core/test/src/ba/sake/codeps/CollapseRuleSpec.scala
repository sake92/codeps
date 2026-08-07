package ba.sake.codeps

import ba.sake.codeps.model.*

class CollapseRuleSpec extends munit.FunSuite:

  test("parse wild") {
    assertEquals(CollapseRule.parse("com.example.**"), Right(CollapseRule.Wild("com.example")))
  }

  test("parse single level") {
    assertEquals(CollapseRule.parse("org.lib.*"), Right(CollapseRule.SingleLevel("org.lib")))
  }

  test("reject middle wildcard") {
    assert(CollapseRule.parse("a.*.b").isLeft)
  }

  test("reject empty prefix") {
    assert(CollapseRule.parse(".**").isLeft)
    assert(CollapseRule.parse(".*").isLeft)
  }

  test("wild matches package and everything below") {
    val rule = CollapseRule.Wild("com.example")
    assertEquals(rule("com.example"), Some("com.example"))
    assertEquals(rule("com.example.foo"), Some("com.example"))
    assertEquals(rule("com.example.bar.baz"), Some("com.example"))
    assertEquals(rule("org.other"), None)
    assertEquals(rule("com.examplex"), None)
  }

  test("single level collapses into next level below prefix") {
    val rule = CollapseRule.SingleLevel("org.lib")
    assertEquals(rule("org.lib.foo.bar"), Some("org.lib.foo"))
    assertEquals(rule("org.lib.baz.qux.x"), Some("org.lib.baz"))
    assertEquals(rule("org.lib.foo"), Some("org.lib.foo"))
    assertEquals(rule("org.other"), None)
  }
