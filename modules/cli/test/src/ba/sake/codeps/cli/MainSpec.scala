package ba.sake.codeps.cli

import ba.sake.codeps.testing.FixtureCompiler

class MainSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  val semdbDir = FixtureCompiler.classesDir / "META-INF" / "semanticdb"

  private def runCli(args: String*): os.CommandResult =
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        args.map(s => s: os.Shellable)
    os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe)

  private def runCliEnv(env: Map[String, String], args: String*): os.CommandResult =
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        args.map(s => s: os.Shellable)
    os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe, env = env)

  private def exportJson(name: String): os.Path =
    val out = os.pwd / "tmp" / "cli-test" / name
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "semanticdb", "-o", out.toString, "--input", semdbDir.toString)
    assertEquals(res.exitCode, 0)
    out

  private def exportJdepsJson(name: String): os.Path =
    val out = os.pwd / "tmp" / "cli-test" / name
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "jdeps", "-o", out.toString, "--input", FixtureCompiler.jdepsFile.toString)
    assertEquals(res.exitCode, 0)
    out

  test("export --from semanticdb emits package and file nodes only") {
    val content = os.read(exportJson("deps.json"))
    assert(content.contains("\"kind\": \"package\""))
    assert(content.contains("\"kind\": \"file\""))
    assert(!content.contains("\"kind\": \"type\""))
    assert(!content.contains("\"kind\": \"member\""))
    // Public symbol metadata and references may mention type ids, but the
    // exported dependency node set remains package/file-only.
    assert(content.contains("com.example.modules.module1")) // packages remain
    assert(content.contains("src/com/example/util/Helper.scala")) // files remain
    assert(content.contains("\"parentId\": \"com.example.util\"")) // file -> package link
  }

  test("export --from jdeps emits package-level json") {
    val out = os.pwd / "tmp" / "cli-test" / "deps-jdeps.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "jdeps", "-o", out.toString, "--input", FixtureCompiler.jdepsFile.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules.module2\""))
    assert(content.contains("\"kind\": \"package\""))
    assert(!content.contains("\"kind\": \"type\""))
    assert(!content.contains("\"kind\": \"member\""))
    assert(!content.contains("\"kind\": \"file\""))
  }

  test("nonexistent input exits 1") {
    val res = runCli("export", "--from", "semanticdb", "--input", "/nonexistent/path")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("input path does not exist"))
  }

  test("partial semanticdb export omits incomplete symbol-use metadata") {
    val input = os.pwd / "tmp" / "cli-test" / "partial-semanticdb"
    val out = os.pwd / "tmp" / "cli-test" / "partial-deps.json"
    os.remove.all(input)
    os.remove.all(out)
    os.makeDir.all(input)
    val valid = os.walk(semdbDir).find(_.ext == "semanticdb").get
    os.write.over(input / "valid.semanticdb", os.read.bytes(valid))
    // Truncated protobuf: one successful document plus one parse failure makes
    // the merged reference index incomplete.
    os.write.over(input / "broken.semanticdb", Array[Byte](10, 127))

    val res = runCli("export", "--from", "semanticdb", "-o", out.toString, "--input", input.toString)
    assertEquals(res.exitCode, 0)
    assert(res.err.text().contains("warning: failed to parse semanticdb"))
    val content = os.read(out)
    assert(!content.contains("symbolReferences"))
    assert(!content.contains("declaredPublicSymbols"))
  }

  test("report-packages emits the flat metrics json") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report-packages", "--format", "json", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"scope\": \"packages\""))
    assert(content.contains("\"generatedAt\""))
    assert(""""generatedAt": "\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z"""".r.findFirstIn(content).nonEmpty) // second precision, UTC
    assert(content.contains("\"summary\""))
    assert(content.contains("\"nodesInCycles\""))
    assert(content.contains("\"cycles\""))
    assert(!content.contains("\"knots\""))
    assert(content.contains("\"surface\""))
    assert(!content.contains("articulation_points"))
    assert(!content.contains("\"effect\""))
    assert(!content.contains("\"new_size\""))
    assert(content.contains("\"com.example.modules.module2\""))
  }

  test("report-files --include selects the package's files") {
    val out = os.pwd / "tmp" / "cli-test" / "report-files.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report-files", "--include", "com.example.util", "--format", "json", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"scope\": \"files\""))
    assert(content.contains("src/com/example/util/Helper.scala"))
    assert(!content.contains("src/com/example/app/Main.scala"))
  }

  test("report-files on jdeps data exits 1") {
    val res = runCli("report-files", "--input", exportJdepsJson("deps-jdeps-v2.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("no file nodes found in the input"))
  }

  test("report-packages on jdeps data works") {
    val res = runCli("report-packages", "--format", "json",
      "--input", exportJdepsJson("deps-jdeps-report.json").toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("com.example.modules.module2"))
  }

  test("report-packages table renders the same data as text") {
    val out = os.pwd / "tmp" / "cli-test" / "report-table.txt"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report-packages", "--format", "table", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.startsWith("scope: packages"))
    assert(content.contains("Summary"))
    assert(content.contains("Cycles"))
    assert(!content.contains("Articulation points"))
    assert(content.contains("Surface"))
  }

  test("report-packages defaults --format to table") {
    val res = runCli("report-packages", "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().startsWith("scope: packages"))
    assert(res.out.text().contains("Summary"))
    assert(res.out.text().contains("Cycles"))
  }

  test("report-packages --all selects the complete table inventory") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val defaultRes = runCli("report-packages", "--input", cyclic.toString)
    val allRes = runCli("report-packages", "--all", "--input", cyclic.toString)
    assertEquals(defaultRes.exitCode, 0)
    assertEquals(allRes.exitCode, 0)
    assert(defaultRes.out.text().contains("Surface risks (top"))
    assert(allRes.out.text().contains("Surface risks (all"))
  }

  test("report-packages on the checked-in cyclic fixture finds the module cycle (homepage example)") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val outJson = os.pwd / "tmp" / "cli-test" / "report-cyclic-fixture.json"
    os.makeDir.all(outJson / os.up)
    os.remove.all(outJson)
    val jsonRes = runCli("report-packages", "--format", "json", "-o", outJson.toString, "--input", cyclic.toString)
    assertEquals(jsonRes.exitCode, 0)
    val content = os.read(outJson)
    assert(content.contains("\"scope\": \"packages\""))
    assert(content.contains("\"id\": \"scc:com.example.modules.module1\""))
    assert(content.contains("\"status\": \"notRequested\""))
    assert(content.contains("\"greedyCutEstimate\": null"))
    assert(content.contains("\"mutPorts\": 0"))
    assert(content.contains("\"nodesInCycles\": 2"))
    // table format renders the same cycle with camelCase headers
    val tableRes = runCli("report-packages", "--input", cyclic.toString)
    assertEquals(tableRes.exitCode, 0)
    assert(tableRes.out.text().contains("scc:com.example.modules.module1"))
    assert(tableRes.out.text().contains("Cycle scc:com.example.modules.module1"))
    assert(tableRes.out.text().contains("cut analysis: notRequested"))
    assert(tableRes.out.text().contains("greedyCutEstimate"))
    assert(!tableRes.out.text().contains("solution 1:"))
    assert(tableRes.out.text().contains("mut"))
    assert(!tableRes.out.text().contains("mutPorts"))
    assert(!tableRes.out.text().contains("mut_ports"))

    val analyzedRes = runCli("report-packages", "--format", "json", "--analyze-cuts", "--cut-time-limit", "1s", "--input", cyclic.toString)
    assertEquals(analyzedRes.exitCode, 0)
    assert(analyzedRes.out.text().contains("\"status\": \"completedExact\""))
    assert(analyzedRes.out.text().contains("\"greedyCutEstimate\": 1"))
  }

  test("report-packages surface columns are repeatable, canonical, and deduplicated") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCli(
      "report-packages",
      "--columns", "mutability",
      "--columns", "visibility",
      "--columns", "core",
      "--columns", "visibility",
      "--input", cyclic.toString
    )
    assertEquals(res.exitCode, 0)
    val surfaceSection = res.out.text().substring(res.out.text().indexOf("Surface risks"), res.out.text().indexOf("Public surface"))
    val header = surfaceSection.linesIterator.find(_.startsWith("node")).get
    assertEquals(
      header.trim.split("\\s+").toSeq,
      Seq("node", "in", "out", "ports", "mut", "encap%", "use", "pub", "prot", "pkg", "priv", "total",
        "pubMut", "protMut", "pkgMut", "privMut", "mut%")
    )
    assert(!header.contains("fanIn"))
    assert(!header.contains("publicSurface"))
  }

  test("report-packages defaults to core surface columns and all exposes the accounting view") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val defaultRes = runCli("report-packages", "--input", cyclic.toString)
    val allRes = runCli("report-packages", "--columns", "all", "--input", cyclic.toString)
    assertEquals(defaultRes.exitCode, 0)
    assertEquals(allRes.exitCode, 0)
    val defaultSection = defaultRes.out.text().substring(defaultRes.out.text().indexOf("Surface risks"), defaultRes.out.text().indexOf("Public surface"))
    val allSection = allRes.out.text().substring(allRes.out.text().indexOf("Surface risks"), allRes.out.text().indexOf("Public surface"))
    val defaultHeader = defaultSection.linesIterator.find(_.startsWith("node")).get
    val allHeader = allSection.linesIterator.find(_.startsWith("node")).get
    assertEquals(defaultHeader.trim.split("\\s+").toSeq, Seq("node", "in", "out", "ports", "mut", "encap%", "use"))
    assertEquals(allHeader.trim.split("\\s+").toSeq, Seq(
      "node", "in", "out", "ports", "mut", "encap%", "use", "pub", "prot", "pkg", "priv",
      "total", "pubMut", "protMut", "pkgMut", "privMut", "mut%", "exp"
    ))
  }

  test("report-packages table omits an empty Orphans section") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCli("report-packages", "--input", cyclic.toString)
    assertEquals(res.exitCode, 0)
    assert(!res.out.text().contains("Orphans"))
  }

  test("surface column selection does not change JSON output") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val env = Map("SOURCE_DATE_EPOCH" -> "1700000000")
    val defaultRes = runCliEnv(env, "report-packages", "--format", "json", "--input", cyclic.toString)
    val allRes = runCliEnv(env, "report-packages", "--format", "json", "--columns", "all", "--input", cyclic.toString)
    assertEquals(defaultRes.exitCode, 0)
    assertEquals(allRes.exitCode, 0)
    assertEquals(defaultRes.out.text(), allRes.out.text())
  }

  test("report surface columns validate group names") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCli("report-packages", "--columns", "unknown", "--input", cyclic.toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("unknown columns group"))
  }

  test("cut analysis candidate budget is a successful bounded report") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCli("report-packages", "--format", "json", "--analyze-cuts", "--cut-candidate-limit", "1", "--input", cyclic.toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("\"status\": \"budgetExceeded\""))
    assert(res.out.text().contains("\"examinedCandidates\": 1"))
  }

  test("cut analysis limits validate and require explicit analysis") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val zeroCandidates = runCli("report-packages", "--cut-candidate-limit", "0", "--input", cyclic.toString)
    assertEquals(zeroCandidates.exitCode, 1)
    assert(zeroCandidates.err.text().contains("require --analyze-cuts"))

    val zeroAnalyzed = runCli("report-packages", "--analyze-cuts", "--cut-candidate-limit", "0", "--input", cyclic.toString)
    assertEquals(zeroAnalyzed.exitCode, 1)
    assert(zeroAnalyzed.err.text().contains("must be positive"))

    val negativeAnalyzed = runCli("report-packages", "--analyze-cuts", "--cut-candidate-limit", "-1", "--input", cyclic.toString)
    assertEquals(negativeAnalyzed.exitCode, 1)
    assert(negativeAnalyzed.err.text().contains("must be positive"))

    val badDuration = runCli("report-packages", "--analyze-cuts", "--cut-time-limit", "0s", "--input", cyclic.toString)
    assertEquals(badDuration.exitCode, 1)
    assert(badDuration.err.text().contains("must be a positive duration"))
  }

  test("inspect-cycle reads the complete v2 cycle detail") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val reportJson = os.pwd / "tmp" / "cli-test" / "inspect-cycle-report.json"
    os.makeDir.all(reportJson / os.up)
    os.remove.all(reportJson)
    val reportRes = runCli("report-packages", "--format", "json", "-o", reportJson.toString, "--input", cyclic.toString)
    assertEquals(reportRes.exitCode, 0)

    val result = runCli("inspect-cycle", "--report", reportJson.toString,
      "--id", "scc:com.example.modules.module1", "--format", "json")
    assertEquals(result.exitCode, 0)
    assert(result.out.text().contains("\"members\": ["))
    assert(result.out.text().contains("\"witnessCycle\": ["))
    assert(result.out.text().contains("\"internalEdges\""))
    assert(result.out.text().contains("\"incomingEdges\""))
    assert(result.out.text().contains("\"outgoingEdges\""))
    assert(result.out.text().contains("\"cutAnalysis\""))

    val table = runCli("inspect-cycle", "--report", reportJson.toString,
      "--id", "scc:com.example.modules.module1")
    assertEquals(table.exitCode, 0)
    assert(table.out.text().contains("edge counts:"))
    assert(table.out.text().contains("cutAnalysis.status: notRequested"))

    val missing = runCli("inspect-cycle", "--report", reportJson.toString, "--id", "missing")
    assertEquals(missing.exitCode, 1)
    assert(missing.err.text().contains("unknown cycle id"))
  }

  test("inspect-node returns surface, cycle affiliation, and matching findings") {
    val reportJson = os.pwd / "tmp" / "cli-test" / "inspect-node-report.json"
    os.makeDir.all(reportJson / os.up)
    os.write.over(reportJson,
      """{
        "schemaVersion": 2,
        "scope": "packages",
        "generatedAt": "2026-08-27T10:00:00Z",
        "summary": {"nodes": 1, "edges": 0, "nodesInCycles": 0, "orphans": 1, "criticalPathLength": 0},
        "cycles": [],
        "propagators": [],
        "surface": [{"node": "p1", "fanIn": 0, "fanOut": 0, "ports": 3, "mutPorts": 1, "exposure": 6, "utilization": null, "cycleId": null}],
        "orphans": ["p1"],
        "findings": [{"id": "mutableSurface:p1", "kind": "mutableSurface", "severity": "high", "subject": "p1", "evidence": "mutPorts=1, exposure=6", "confidence": "high", "nextAction": "inspect-node p1"}]
      }""")

    val result = runCli("inspect-node", "--report", reportJson.toString, "--id", "p1", "--format", "json")
    assertEquals(result.exitCode, 0)
    assert(result.out.text().contains("\"surface\""))
    assert(result.out.text().contains("\"cycleId\": null"))
    assert(result.out.text().contains("mutableSurface:p1"))

    val missing = runCli("inspect-node", "--report", reportJson.toString, "--id", "missing")
    assertEquals(missing.exitCode, 1)
    assert(missing.err.text().contains("unknown node id"))
  }

  test("inspection rejects an incompatible report schema") {
    val reportJson = os.pwd / "tmp" / "cli-test" / "inspect-schema-report.json"
    os.makeDir.all(reportJson / os.up)
    os.write.over(reportJson,
      """{"schemaVersion": 1, "scope": "packages", "generatedAt": "2026-08-27T10:00:00Z", "summary": {"nodes": 0, "edges": 0, "nodesInCycles": 0, "orphans": 0, "criticalPathLength": 0}, "cycles": [], "propagators": [], "surface": [], "orphans": [], "findings": []}""")
    val result = runCli("inspect-node", "--report", reportJson.toString, "--id", "missing")
    assertEquals(result.exitCode, 1)
    assert(result.err.text().contains("incompatible schema version"))
  }

  test("report-packages --skip-tests excludes test nodes") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2-skip-tests.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report-packages", "--skip-tests", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(!content.contains("HelperSpec"))
    assert(!content.contains("OnlyTestsHereSpec"))
  }

  test("report-packages -c collapse merges packages") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2-collapse.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report-packages", "-c", "com.example.modules.**", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("com.example.modules"))
  }

  test("report-packages --test-pattern without --skip-tests exits 1") {
    val res = runCli("report-packages", "--test-pattern", "**/test/**", "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("--test-pattern requires --skip-tests"))
  }

  test("legacy report command is rejected") {
    val res = runCli("report", "--scope", "bogus", "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
  }

  test("report-packages with a bad format exits non-zero") {
    val res = runCli("report-packages", "--format", "bogus", "--input", exportJson("deps.json").toString)
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown format: bogus"))
  }

  test("malformed json input exits 1") {
    val input = os.pwd / "tmp" / "cli-test" / "bad.json"
    os.makeDir.all(input / os.up)
    os.write.over(input, """{"nodes": "not-an-array"}""")
    val res = runCli("report-packages", "--input", input.toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("failed to parse json"))
  }

  test("report-packages on a directory input exits 1 with a clean error") {
    val res = runCli("report-packages", "--input", "testFixtures")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("not a file"))
    assert(!res.err.text().contains("Is a directory")) // no raw stack trace
  }

  test("report-packages reads from stdin with --input -") {
    val json = os.read(exportJson("deps.json"))
    val out = os.pwd / "tmp" / "cli-test" / "report-stdin.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        Seq[os.Shellable]("report-packages", "--format", "json", "-o", out.toString, "--input", "-")
    val res = os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe, stdin = json)
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("\"summary\""))
  }

  test("unknown flag exits 1 with a clean error") {
    val res = runCli("report-packages", "--bogus", "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("\"--bogus\"")) // mainargs' own unknown-argument error
    assert(!res.err.text().contains("Exception"))  // no raw stack trace
  }

  test("positional token is rejected by the strict parser") {
    val res = runCli("report-packages", "/tmp/opencode/nonexistent-pos.json")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("Unknown argument"))
    assert(!res.err.text().contains("Exception")) // no raw stack trace
  }

  test("--version prints the version") {
    val res = runCli("--version")
    assertEquals(res.exitCode, 0)
    assert(res.out.text().trim.matches("[0-9A-Za-z.\\-]+"))
  }

  test("report-packages --help prints usage") {
    val res = runCli("report-packages", "--help")
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("Available subcommands"))
    assert(res.out.text().contains("--columns"))
    assert(res.out.text().contains("core, visibility, mutability, coupling, or all"))
  }

  test("SOURCE_DATE_EPOCH pins generatedAt") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCliEnv(Map("SOURCE_DATE_EPOCH" -> "1700000000"),
      "report-packages", "--format", "json", "--input", cyclic.toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("\"generatedAt\": \"2023-11-14T22:13:20Z\""))
  }

  test("invalid SOURCE_DATE_EPOCH exits 1") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCliEnv(Map("SOURCE_DATE_EPOCH" -> "abc"),
      "report-packages", "--format", "json", "--input", cyclic.toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("invalid SOURCE_DATE_EPOCH"))
  }
