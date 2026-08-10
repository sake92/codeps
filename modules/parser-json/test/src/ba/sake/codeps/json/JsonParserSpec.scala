package ba.sake.codeps.json

import ba.sake.codeps.model.{PackageDeps, PackageEdge, PkgStats}
import ba.sake.tupson.{*, given}

class JsonParserSpec extends munit.FunSuite:

  test("parses valid input with own packages, edges and stats") {
    val text = """{
      |  "own": ["com.example.a"],
      |  "edges": [{"source": "com.example.a", "target": "com.example.b"}],
      |  "stats": {"com.example.a": {"fileCount": 3, "classCount": 5}}
      |}""".stripMargin
    val result = JsonParser.parse(text)
    assertEquals(
      result,
      Right(PackageDeps(Set("com.example.a"), Set(PackageEdge("com.example.a", "com.example.b")), Map("com.example.a" -> PkgStats(3, 5))))
    )
  }

  test("missing own/edges default to empty") {
    val result = JsonParser.parse("""{"stats": {}}""")
    assertEquals(result, Right(PackageDeps(Set.empty, Set.empty)))
  }

  test("empty object parses to empty deps") {
    assertEquals(JsonParser.parse("{}"), Right(PackageDeps.empty))
  }

  test("unknown keys are ignored") {
    val result = JsonParser.parse("""{"own": ["a"], "edges": [], "bogus": 42}""")
    assertEquals(result, Right(PackageDeps(Set("a"), Set.empty)))
  }

  test("wrong type for edges is reported as Left") {
    val result = JsonParser.parse("""{"own": ["a"], "edges": "not-an-array"}""")
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("failed to parse json"))
  }

  test("missing required field inside a stats entry is reported as Left") {
    val result = JsonParser.parse("""{"stats": {"a": {"fileCount": 1}}}""")
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("classCount"))
  }

  test("malformed json is reported as Left") {
    val result = JsonParser.parse("{not json")
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("failed to parse json"))
  }

  test("toJson/parseJson round-trip") {
    val deps = PackageDeps(
      Set("com.example.a", "com.example.b"),
      Set(PackageEdge("com.example.a", "com.example.b")),
      Map("com.example.a" -> PkgStats(3, 5))
    )
    val reparsed = deps.toJson(spaces = 0, sort = false).parseJson[PackageDeps]
    assertEquals(reparsed, deps)
  }
