package ba.sake.codeps.report

class PrefixStripperSpec extends munit.FunSuite:

  test("no stripping below 2 ids") {
    assertEquals(PrefixStripper.strip(Seq("a.b.C")), (None, Map.empty))
    assertEquals(PrefixStripper.strip(Seq.empty), (None, Map.empty))
  }

  test("strips common prefix at dot boundary") {
    assertEquals(
      PrefixStripper.strip(Seq("a.b.C", "a.b.D")),
      (Some("a.b."), Map("a.b.C" -> "C", "a.b.D" -> "D"))
    )
  }

  test("strips package prefix across the whole path") {
    assertEquals(
      PrefixStripper.strip(Seq("org.sake.mod.a.A", "org.sake.mod.b.B")),
      (Some("org.sake.mod."), Map("org.sake.mod.a.A" -> "a.A", "org.sake.mod.b.B" -> "b.B"))
    )
  }

  test("rewinds to the last dot when the common prefix ends mid-segment") {
    assertEquals(
      PrefixStripper.strip(Seq("a.bcX", "a.bcY")),
      (Some("a."), Map("a.bcX" -> "bcX", "a.bcY" -> "bcY"))
    )
  }

  test("does not strip a partial first segment") {
    assertEquals(PrefixStripper.strip(Seq("abX", "abY")), (None, Map.empty))
  }

  test("ids that would become empty fall back to the full id") {
    assertEquals(
      PrefixStripper.strip(Seq("a.", "a.b")),
      (Some("a."), Map("a." -> "a.", "a.b" -> "b"))
    )
  }

  test("prefix that is the whole shorter id is handled") {
    assertEquals(
      PrefixStripper.strip(Seq("a.b", "a.b.c")),
      (Some("a."), Map("a.b" -> "b", "a.b.c" -> "b.c"))
    )
  }

  test("no common prefix -> no stripping") {
    assertEquals(PrefixStripper.strip(Seq("x.A", "y.B")), (None, Map.empty))
    assertEquals(PrefixStripper.strip(Seq("a.X", "X")), (None, Map.empty))
  }

  test("duplicate ids are deduplicated") {
    assertEquals(
      PrefixStripper.strip(Seq("a.b.C", "a.b.C", "a.b.D")),
      (Some("a.b."), Map("a.b.C" -> "C", "a.b.D" -> "D"))
    )
  }

  test("strips common prefix at file path boundary") {
    assertEquals(
      PrefixStripper.strip(Seq("server/src/a/A.scala", "server/src/b/B.scala"), '/'),
      (Some("server/src/"), Map(
        "server/src/a/A.scala" -> "a/A.scala",
        "server/src/b/B.scala" -> "b/B.scala"
      ))
    )
  }

  test("does not strip a partial file path segment") {
    assertEquals(
      PrefixStripper.strip(Seq("serverA/src/a/A.scala", "serverB/src/b/B.scala"), '/'),
      (None, Map.empty)
    )
  }

  test("does not strip a file prefix absent from every id") {
    assertEquals(
      PrefixStripper.strip(Seq("server/src/a/A.scala", "deder-common/src/b/B.scala"), '/'),
      (None, Map.empty)
    )
  }
