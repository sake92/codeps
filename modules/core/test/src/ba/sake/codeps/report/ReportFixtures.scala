package ba.sake.codeps.report

import ba.sake.codeps.artifacts.models.*
import java.time.Instant

private[report] object ReportFixtures {
  val report = ScopeReport(
    3,
    "packages",
    Instant.parse("2026-08-27T10:00:00Z"),
    Summary(0, 0, 0, 0),
    Nil,
    Nil,
    Nil,
    Nil,
    Nil,
    None
  )
  val cycle = Cycle("scc:a", Nil, Nil, 2, 0, 0, 0, 0, CutAnalyzer.notRequested)
  val surface = Surface(
    node = "p",
    fanIn = 0,
    fanOut = 0,
    ports = 0,
    mutPorts = 0,
    exposure = 0,
    publicSurface = 0,
    protectedSurface = 0,
    packageSurface = 0,
    privateSurface = 0,
    publicMutableSurface = 0,
    protectedMutableSurface = 0,
    packageMutableSurface = 0,
    privateMutableSurface = 0,
    totalDeclaredSurface = 0,
    dependentsPerPublicPort = None,
    cycleId = None,
    encapsulationRatio = None,
    publicMutableRatio = None
  )
}
