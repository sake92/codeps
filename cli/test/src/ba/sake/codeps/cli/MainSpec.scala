package ba.sake.codeps.cli

import ba.sake.codeps.testing.FixtureCompiler

class MainSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  val semdbDir = FixtureCompiler.classesDir / "META-INF" / "semanticdb"

  test("semdb subcommand produces dot output file") {
    val out = os.pwd / "tmp" / "cli-test" / "out.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val code = Main.run(
      Array("semdb", semdbDir.toString, "--include", "com.example", "-f", "dot", "-o", out.toString)
    )
    assertEquals(code, 0)
    val content = os.read(out)
    assert(content.startsWith("digraph deps {"))
    assert(content.contains("\"com.example.modules.module1\" -> \"com.example.util\";"))
  }

  test("semdb subcommand collapses packages") {
    val out = os.pwd / "tmp" / "cli-test" / "out-collapsed.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val code = Main.run(
      Array(
        "semdb", semdbDir.toString,
        "--include", "com.example",
        "--collapse", "com.example.modules.**",
        "-f", "dot", "-o", out.toString
      )
    )
    assertEquals(code, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules\" -> \"com.example.util\";"))
  }

  test("jdeps subcommand works") {
    val out = os.pwd / "tmp" / "cli-test" / "out-jdeps.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val code = Main.run(
      Array("jdeps", FixtureCompiler.jdepsFile.toString, "--include", "com.example", "-f", "json", "-o", out.toString)
    )
    assertEquals(code, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules.module1\""))
  }

  test("empty result exits 1") {
    val code = Main.run(
      Array("semdb", semdbDir.toString, "--include", "no.such.pkg", "-f", "dot")
    )
    assertEquals(code, 1)
  }

  test("nonexistent input exits 1") {
    val code = Main.run(
      Array("semdb", "/nonexistent/path", "--include", "com.example", "-f", "dot")
    )
    assertEquals(code, 1)
  }

  test("bad format exits non-zero") {
    val code = Main.run(
      Array("semdb", semdbDir.toString, "--include", "com.example", "-f", "bogus")
    )
    assert(code != 0)
  }

  test("bad collapse rule exits 1") {
    val code = Main.run(
      Array("semdb", semdbDir.toString, "--include", "com.example", "--collapse", "a.b.c", "-f", "dot")
    )
    assertEquals(code, 1)
  }
