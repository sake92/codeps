package ba.sake.codeps.testing

/** Compiles the checked-in example sources once into ./tmp/examples/example1 (gitignored, never cleaned). */
object FixtureCompiler:

  val exampleDir = os.pwd / "tmp" / "examples" / "example1"
  val classesDir = exampleDir / "classes"
  val jdepsFile  = exampleDir / "jdeps.txt"
  private val marker = exampleDir / ".done"
  private val lockDir = exampleDir / ".lock"
  private val lockTimeoutMs = 120_000L

  /** Ensures fixtures exist; compiles once per run, reuses the cache afterwards.
    * Safe to call concurrently from multiple test JVMs (deder runs suites in parallel forks):
    * exactly one caller compiles, the others wait for it and reuse the result. */
  def ensure(): Unit =
    var done = false
    while !done do
      if os.exists(marker) then
        checkConsistent()
        done = true
      else if acquireLock() then
        try
          // re-check: the previous holder may have finished while we waited for the lock
          if !os.exists(marker) then compileFixtures()
          else checkConsistent()
        finally releaseLock()
        done = true
      else
        waitForLockRelease() // throws on timeout

  private def compileFixtures(): Unit =
    os.makeDir.all(exampleDir)
    os.copy.over(os.pwd / "test" / "resources" / "examples" / "example1", exampleDir / "src")
    os.proc("scala-cli", "compile", "--server=false", "--semanticdb", "-d", classesDir, exampleDir / "src")
      .call(check = true)
    val jdepsResult = os.proc("jdeps", "-verbose:package", "-filter:none", "-cp", classesDir, classesDir)
      .call(check = true)
    os.write.over(jdepsFile, jdepsResult.out.text())
    os.write(marker, "")

  private def checkConsistent(): Unit =
    if !os.exists(classesDir) then
      sys.error(s"fixture marker exists but $classesDir is missing; delete $exampleDir and re-run")

  /** mkdir is atomic: exactly one caller wins, the rest wait. */
  private def acquireLock(): Boolean =
    try
      os.makeDir.all(exampleDir)
      os.makeDir(lockDir)
      true
    catch
      case _: java.nio.file.FileAlreadyExistsException => false

  private def releaseLock(): Unit =
    os.remove.all(lockDir)

  /** Blocks until the lock holder finishes (success) or gives up (failure).
    * On timeout, errors out — otherwise the retry loop above would spin forever. */
  private def waitForLockRelease(): Unit =
    val deadline = System.currentTimeMillis() + lockTimeoutMs
    while os.exists(lockDir) && System.currentTimeMillis() < deadline do Thread.sleep(50)
    if os.exists(lockDir) then
      sys.error(s"timed out waiting for fixture lock at $lockDir; delete $exampleDir and re-run")

  def semanticdbFiles: Seq[os.Path] =
    os.walk(classesDir / "META-INF" / "semanticdb").filter(_.ext == "semanticdb")
