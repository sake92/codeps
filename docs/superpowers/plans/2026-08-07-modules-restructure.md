# Modules Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all Deder build modules under `modules/` (flattening `parsers/`) and rename the shared test fixtures from `test/resources/examples` to a top-level `testFixtures/`.

**Architecture:** Pure directory restructure + config/code reference updates. Module **ids** (`core`, `parser-jdeps`, `cli`, …) stay unchanged — only Deder `root` paths in `deder.pkl` change. The only code reference to a module-relative path is `FixtureCompiler.scala` (fixture sources copy). Nothing in Scala imports changes (package names are unaffected by directory layout).

**Tech Stack:** Deder v0.20.0 build (Pkl config), scala-cli, munit, os-lib.

**Key facts verified:**
- `deder.pkl` defines 6 module roots: `core`, `parsers/semanticdb`, `parsers/jdeps`, `export`, `cli`, and `test-utils` (id `test-utils`; `root` defaults to `id` per Deder schema — so it must get an explicit `root` after the move). Test modules derive roots as `<root>/test` automatically.
- `test/` is **not** a Deder module — only shared fixture sources. No deder.pkl entry references it.
- `FixtureCompiler.scala` is the only code reference to `test/resources/examples`.
- `tmp/`, `.deder/`, `.basamake/`, `.bsp/` are gitignored generated state — no action needed.
- Historical docs (`docs/superpowers/plans/2026-08-05-codeps.md`, `docs/superpowers/specs/2026-08-05-codeps-design.md`) contain old paths — **leave untouched** (they are a dated historical record of the original plan).
- **Pre-existing bug discovered during baseline verification in a fresh worktree:** `FixtureCompiler.ensure()` is not safe under concurrent calls. Deder runs test suites in parallel forks; on a fresh `tmp/` (no `.done` marker), multiple test JVMs all enter the "compile" branch and race to write `tmp/examples/example1/jdeps.txt`, crashing with `FileAlreadyExistsException`. Fixed in Task 0 (required so Task 2's fresh-`tmp` verification is reliable).

**Target layout after all tasks:**
```
modules/
  cli/                  core/            export/
  parser-jdeps/         parser-semanticdb/   test-utils/
testFixtures/
  example1/src/...
```

---

### Task 0: Make `FixtureCompiler.ensure()` race-safe

**Why:** Deder runs test suites in parallel forks. On a fresh `tmp/`, `parser-semanticdb-test`, `parser-jdeps-test`, and `cli-test` all call `FixtureCompiler.ensure()` at the same time; all see no `.done` marker; all copy/compile/write — `os.write(jdepsFile, …)` throws `FileAlreadyExistsException` for the losers (demonstrated: `java.nio.file.FileAlreadyExistsException: .../tmp/examples/example1/jdeps.txt` at `FixtureCompiler.scala:20`). In the main workspace this never surfaced because `tmp/` was already cached.

**Fix:** exactly-one-wins protocol using an atomic `os.makeDir(lockDir)` (mkdir is atomic across processes). The winner compiles; losers wait for the lock to disappear, then re-evaluate (marker present → reuse; lock holder failed → retry as the new holder). Timeout with a clear error instead of spinning forever.

**Files:**
- Modify: `test-utils/src/ba/sake/codeps/testing/FixtureCompiler.scala` (whole file below)

- [ ] **Step 1: Replace `FixtureCompiler.scala` with the race-safe version**

Replace the entire file content with:

```scala
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
```

Note the two behavior changes beyond the lock protocol:
- `os.write(jdepsFile, …)` → `os.write.over(jdepsFile, …)` — survives a crashed partial run (lock holder died after writing `jdeps.txt` but before writing the marker).
- Docstring documents the concurrency contract.

No unit test is added: the fixture is shared mutable state across parallel test JVMs, so a unit test cannot safely reset it (deleting `tmp/examples/example1` under a concurrently running suite would break it). The regression test is integration-level: fresh `tmp/` + full parallel suite, repeated.

- [ ] **Step 2: Run the race scenario (pre-fix this failed)**

Run (from repo root in the worktree):
```bash
rm -rf tmp && deder exec -t test
```
Expected: all suites pass (8 suites, 36 tests). Repeat **two more times** (3 total fresh-`tmp` runs) — each run re-exercises the parallel-fork race from scratch; all must pass.

- [ ] **Step 3: Commit**

```bash
git add test-utils/src/ba/sake/codeps/testing/FixtureCompiler.scala
git commit -m "fix(test-utils): make FixtureCompiler.ensure() race-safe"
```

---

### Task 1: Move all modules under `modules/`

**Files:**
- Move: `cli/`, `core/`, `export/`, `test-utils/` → `modules/<same-name>/`
- Move: `parsers/jdeps/` → `modules/parser-jdeps/`, `parsers/semanticdb/` → `modules/parser-semanticdb/` (flattened; `parsers/` container disappears)
- Modify: `deder.pkl:19,32-38,41,58,72,84`

- [ ] **Step 1: `git mv` the module directories**

```bash
mkdir -p modules
git mv cli modules/cli
git mv core modules/core
git mv export modules/export
git mv test-utils modules/test-utils
git mv parsers/jdeps modules/parser-jdeps
git mv parsers/semanticdb modules/parser-semanticdb
rmdir parsers   # empty container; git doesn't track empty dirs
```

- [ ] **Step 2: Update `root` paths in `deder.pkl`**

Edit these lines (module ids stay the same):
- Line 19: `root = "core"` → `root = "modules/core"`
- Line 41: `root = "parsers/semanticdb"` → `root = "modules/parser-semanticdb"`
- Line 58: `root = "parsers/jdeps"` → `root = "modules/parser-jdeps"`
- Line 72: `root = "export"` → `root = "modules/export"`
- Line 84: `root = "cli"` → `root = "modules/cli"`
- In the `testUtils` block (lines 32–38), add an explicit root (currently defaults to `id`):
```pkl
local const testUtils = (baseModule) {
  id = "test-utils"
  root = "modules/test-utils"
  moduleDeps { coreModules.main }
  deps {
    "com.lihaoyi::os-lib:0.11.8"
  }
}
```

- [ ] **Step 3: Verify module discovery**

Run: `deder modules --format json`
Expected: all 11 modules listed (core, parser-semanticdb, parser-jdeps, export, cli, test-utils + their 5 test modules — test-utils has no test module) — ids unchanged.

- [ ] **Step 4: Run the test suite**

Run: `deder exec -t test`
Expected: all tests pass (fixture path still `test/resources/examples` — untouched in this task).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(build): move all modules under modules/"
```

---

### Task 2: Rename `test/resources/examples` → top-level `testFixtures/`

**Files:**
- Move: `test/resources/examples/` → `testFixtures/` (collapses `test/resources`; empty tree removed)
- Modify: `modules/test-utils/src/ba/sake/codeps/testing/FixtureCompiler.scala` (the `compileFixtures()` copy path)

- [ ] **Step 1: `git mv` the fixtures directory**

```bash
git mv test/resources/examples testFixtures
rmdir test/resources test   # remove now-empty dirs
```

- [ ] **Step 2: Update the fixture copy path in `FixtureCompiler.scala`**

In `compileFixtures()` (inside `modules/test-utils/src/ba/sake/codeps/testing/FixtureCompiler.scala`), before:
```scala
os.copy.over(os.pwd / "test" / "resources" / "examples" / "example1", exampleDir / "src")
```
After:
```scala
os.copy.over(os.pwd / "testFixtures" / "example1", exampleDir / "src")
```

No other code changes — `exampleDir` (`tmp/examples/example1`) is unchanged.

- [ ] **Step 3: Clear the stale fixture cache and run the full suite**

The cached compiled fixture in `tmp/` must be deleted so `FixtureCompiler.ensure()` re-copies from the new path (otherwise the rename is never exercised):

```bash
rm -rf tmp
deder exec -t test
```

Expected: all tests pass (8 suites, 36 tests); `tmp/examples/example1/.done` is recreated; semanticdb/jdeps specs and the CLI subprocess specs (`MainSpec`) still pass.

- [ ] **Step 4: Verify nothing else references old paths**

Run: `git grep -n "resources/examples"` and `git grep -n '"test" / "resources"'`
Expected: no matches in tracked files (docs under `docs/superpowers/` are the known, intentionally-kept historical references — confirm they were excluded).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(test): rename test/resources/examples to testFixtures"
```

---

**Notes for the executor:**
- All work happens in the worktree at `~/.config/superpowers/worktrees/semdb-packages-deps/modules-restructure` (branch `modules-restructure`); `main` stays untouched.
- Deder's server state (`.deder/`) is gitignored; if the long-running server reports stale module roots after Task 1, run `deder stop` once and re-run `deder modules --format json`.
- No TDD here beyond Task 0's integration verification — these are moves/renames; the existing suite (munit, run via `deder exec -t test`) is the verification.

**Self-review:** Task 0 fixes the demonstrated race (verified red pre-fix); Task 1 covers "move all deder modules under modules/" (all 6 roots updated, `test-utils` root default handled); Task 2 covers "rename test/resources/examples to testFixtures" (move + the single code reference). No placeholders. Paths in commands match the verified current layout.
