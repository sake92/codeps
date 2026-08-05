package ba.sake.codeps.testing

/** Compiles the checked-in example sources once into ./tmp/examples/example1 (gitignored, never cleaned). */
object FixtureCompiler:

  val exampleDir = os.pwd / "tmp" / "examples" / "example1"
  val classesDir = exampleDir / "classes"
  val jdepsFile  = exampleDir / "jdeps.txt"
  private val marker = exampleDir / ".done"

  /** Ensures fixtures exist; compiles once per run, reuses the cache afterwards. */
  def ensure(): Unit =
    if !os.exists(marker) then
      os.makeDir.all(exampleDir)
      os.copy.over(os.pwd / "test" / "resources" / "examples" / "example1", exampleDir / "src")
      os.proc("scala-cli", "compile", "--semanticdb", "-d", classesDir, exampleDir / "src")
        .call(check = true)
      val jdepsResult = os.proc("jdeps", "-verbose:package", "-filter:none", "-cp", classesDir, classesDir)
        .call(check = true)
      os.write(jdepsFile, jdepsResult.out.text())
      os.write(marker, "")
    else if !os.exists(classesDir) then
      sys.error(s"fixture marker exists but $classesDir is missing; delete $exampleDir and re-run")

  def semanticdbFiles: Seq[os.Path] =
    os.walk(classesDir / "META-INF" / "semanticdb").filter(_.ext == "semanticdb")
