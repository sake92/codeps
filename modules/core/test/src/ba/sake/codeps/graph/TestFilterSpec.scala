package ba.sake.codeps.graph

import ba.sake.codeps.model.*

class TestFilterSpec extends munit.FunSuite:

  private val graph = DepsGraph(
    Set(
      Node("com.example", NodeKind.`package`),
      Node("com.example.testpkg", NodeKind.`package`),
      Node("com.example.specs", NodeKind.`package`),
      Node("src/com/example/Foo.scala", NodeKind.file),
      Node("src/com/example/test/FooSpec.scala", NodeKind.file),
      Node("com.example.Foo", NodeKind.`type`, Some("com.example"), Some("src/com/example/Foo.scala")),
      Node("com.example.Foo#bar", NodeKind.member, Some("com.example.Foo"), Some("src/com/example/Foo.scala")),
      Node("com.example.FooSpec", NodeKind.`type`, Some("com.example"), Some("src/com/example/test/FooSpec.scala")),
      Node("com.example.FooSpec#run", NodeKind.member, Some("com.example.FooSpec"), Some("src/com/example/test/FooSpec.scala")),
      Node("com.example.specs.OnlyTestsHereSpec", NodeKind.`type`, Some("com.example.specs"), Some("src/com/example/specs/OnlyTestsHereSpec.scala")),
      // package id contains "test" but children are main code: must NOT be matched by id
      Node("com.example.testpkg.Thing", NodeKind.`type`, Some("com.example.testpkg"), Some("src/com/example/testpkg/Thing.scala")),
      // jdeps-style: no file attribute, never matched
      Node("com.example.LegacyType", NodeKind.`type`, Some("com.example"))
    ),
    Set(
      Edge("com.example.Foo#bar", "com.example.FooSpec"),
      Edge("com.example.FooSpec", "com.example.Foo#bar"),
      Edge("com.example.specs.OnlyTestsHereSpec", "com.example.Foo"),
      Edge("com.example.Foo", "com.example.testpkg.Thing")
    )
  )

  private val filtered = TestFilter.skipTests(graph, Seq("**/test/**", "**/*Spec.scala"))

  test("file nodes matched by id, type/member nodes by their file attribute") {
    val ids = filtered.nodes.map(_.id)
    assert(!ids.contains("src/com/example/test/FooSpec.scala"))
    assert(!ids.contains("com.example.FooSpec"))
    assert(!ids.contains("com.example.FooSpec#run"))
    assert(!ids.contains("com.example.specs.OnlyTestsHereSpec"))
    assert(ids.contains("src/com/example/Foo.scala"))
    assert(ids.contains("com.example.Foo"))
    assert(ids.contains("com.example.Foo#bar"))
  }

  test("package nodes and file-less (jdeps-style) nodes never match") {
    val ids = filtered.nodes.map(_.id)
    assert(ids.contains("com.example.testpkg")) // kept: it has a surviving child
    assert(ids.contains("com.example.testpkg.Thing"))
    assert(ids.contains("com.example.LegacyType"))
  }

  test("edges with an excluded endpoint are dropped; others kept") {
    assertEquals(filtered.edges, Set(Edge("com.example.Foo", "com.example.testpkg.Thing")))
  }

  test("test-only packages are pruned; packages with main content survive") {
    val ids = filtered.nodes.map(_.id)
    assert(!ids.contains("com.example.specs"))
    assert(ids.contains("com.example"))
    assert(ids.contains("com.example.testpkg"))
  }

  test("default patterns cover the documented layouts") {
    assertEquals(
      TestFilter.defaultPatterns,
      Seq(
        "**/test/**",
        "**/*.test.scala",
        "**/*Spec.scala",
        "**/*Test.scala",
        "**/*Tests.scala",
        "**/*Suite.scala",
        "**/*Spec.java",
        "**/*Test.java",
        "**/*Tests.java",
        "**/*Suite.java"
      )
    )
  }
