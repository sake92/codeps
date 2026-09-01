package ba.sake.codeps.report

import ba.sake.codeps.graph.{Collapser, Filter, TarjanScc, TestFilter}
import ba.sake.codeps.model.*
import scala.collection.mutable

/** The language-agnostic metrics layer (v2.md §4): takes a node/edge list whose
  * per-node `isExposed`/`ports`/`mutPorts` were resolved by an extraction backend,
  * and derives every metric fresh from the edge list on each run — nothing here
  * knows Scala syntax, and nothing is cached or stored between runs. */
object MetricsCalculator:

  enum Scope:
    case Packages, Files

  /** The scope-level graph plus per-scope-node port sums. `ports`/`mutPorts` are
    * the summed contributions of all nodes aggregated into each scope node. */
  case class ScopeGraph(
      nodes: Set[String],
      edges: Set[Edge],
      ports: Map[String, Double],
      mutPorts: Map[String, Double]
  )

  def scopeGraph(graph: DepsGraph, scope: Scope): Either[String, ScopeGraph] =
    val nodesById = graph.nodes.map(n => n.id -> n).toMap
    def aggId(n: Node): Option[String] = scope match
      case Scope.Packages =>
        if n.kind == NodeKind.`package` then Some(n.id) else n.rootPackageId(nodesById)
      case Scope.Files =>
        if n.kind == NodeKind.file then Some(n.id) else n.file
    val mapped = graph.nodes.toSeq.flatMap(n => aggId(n).map(id => n -> id))
    if mapped.isEmpty then
      Left(
        scope match
          case Scope.Packages => "no nodes remain after filtering"
          case Scope.Files    => "no file nodes found in the input (jdeps data has no file-level info)"
      )
    else
      val ids = mapped.map(_._2).toSet
      val ports = mapped.groupMapReduce(_._2)(_._1.ports)(_ + _)
      val mutPorts = mapped.groupMapReduce(_._2)(_._1.mutPorts)(_ + _)
      val edges = graph.edges.toSeq
        .flatMap { e =>
          for
            s <- nodesById.get(e.source).flatMap(aggId)
            t <- nodesById.get(e.target).flatMap(aggId)
            if s != t
          yield ((s, t), e.weight)
        }
        .groupMapReduce(_._1)(_._2)(_ + _)
        .map { case ((s, t), w) => Edge(s, t, w) }
        .toSet
      Right(ScopeGraph(ids, edges, ports, mutPorts))

  /** Applies collapse rules; port sums follow the same id mapping as the nodes. */
  def collapse(sg: ScopeGraph, rules: Seq[CollapseRule]): ScopeGraph =
    if rules.isEmpty then sg
    else
      val resolve = Collapser.resolveWith(rules)
      val (ids, edges) = Collapser.collapse(sg.nodes, sg.edges, rules)
      ScopeGraph(ids, edges, reSum(sg.ports, resolve), reSum(sg.mutPorts, resolve))

  private def reSum(perId: Map[String, Double], resolve: String => String): Map[String, Double] =
    perId.toSeq.groupMapReduce((id, v) => resolve(id))(_._2)(_ + _)

  // ---------- metrics ----------

  /** Full pipeline: filter -> skip-tests? -> scope aggregation -> collapse -> metrics.
    * Every metric is derived fresh from the resulting node/edge list on this call. */
  def run(
      graph: DepsGraph,
      scope: Scope,
      includes: Seq[String] = Nil,
      excludes: Seq[String] = Nil,
      collapseRules: Seq[CollapseRule] = Nil,
      testPatterns: Option[Seq[String]] = None,
      cutAnalysisBudget: Option[CutAnalysisBudget] = None
  ): Either[String, MetricsReport] =
    for
      generatedAt <- generatedAt()
      filtered = Filter(graph, includes, excludes)
      scoped = testPatterns match
        case Some(patterns) => TestFilter.skipTests(filtered, patterns)
        case None           => filtered
      sg <- scopeGraph(scoped, scope)
    yield compute(collapse(sg, collapseRules), scope, generatedAt, cutAnalysisBudget)

  /** Real clock by default; SOURCE_DATE_EPOCH (reproducible-builds.org standard,
    * epoch seconds) pins generatedAt for deterministic CI diffs. */
  private def generatedAt(): Either[String, String] =
    sys.env.get("SOURCE_DATE_EPOCH") match
      case None => Right(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString)
      case Some(raw) => raw.toLongOption match
        case Some(epoch) => Right(java.time.Instant.ofEpochSecond(epoch).toString)
        case None        => Left(s"invalid SOURCE_DATE_EPOCH: $raw (expected epoch seconds)")

  private def compute(
      sg: ScopeGraph,
      scope: Scope,
      generatedAt: String,
      cutAnalysisBudget: Option[CutAnalysisBudget]
  ): MetricsReport =
    val fanIn = sg.edges.groupMapReduce(_.target)(_ => 1)(_ + _)
    val fanOut = sg.edges.groupMapReduce(_.source)(_ => 1)(_ + _)
    def fanInOf(id: String): Int = fanIn.getOrElse(id, 0)
    def fanOutOf(id: String): Int = fanOut.getOrElse(id, 0)

    val orphans = sg.nodes.filter(n => fanInOf(n) == 0 && fanOutOf(n) == 0).toSeq.sorted
    val cycleSets = TarjanScc.cycles(sg.nodes, sg.edges)
    val cycleIdByNode = cycleSets.flatMap(scc => scc.map(_ -> ("scc:" + scc.min))).toMap
    val cycles = cycleSets.map(k => cycleRow(k, sg, cutAnalysisBudget)).sortBy(k => (-k.size, -k.extFanIn, k.id))

    val surface = sg.nodes.toSeq
      .map { id =>
        val p = sg.ports.getOrElse(id, 0.0)
        val mp = sg.mutPorts.getOrElse(id, 0.0)
        val fi = fanInOf(id)
        SurfaceRow(
          node = id,
          fanIn = fi,
          fanOut = fanOutOf(id),
          ports = p,
          mutPorts = mp,
          exposure = p + mp * 3,
          utilization = if fi > 0 && p > 0 then Some(fi / p) else None,
          cycleId = cycleIdByNode.get(id)
        )
      }
      .sortBy(r => (if r.utilization.isEmpty then 1 else 0, r.utilization.getOrElse(0.0), r.node))

    val avgFanIn = if sg.nodes.isEmpty then 0.0 else sg.edges.size.toDouble / sg.nodes.size
    val avgFanOut = avgFanIn // sum(fanIn) == sum(fanOut) == edges.size
    val propagators =
      if avgFanIn <= 0.0 then Seq.empty
      else
        sg.nodes.toSeq
          .map { id =>
            val score = (fanInOf(id) / avgFanIn + fanOutOf(id) / avgFanOut) / 2.0
            PropagatorRow(id, fanInOf(id), fanOutOf(id), score)
          }
          .filter(_.score > 1.0)
          .sortBy(r => (-r.score, r.node))
          // Keep the report index complete. ReportTable applies the human-facing
          // top-10 bound at the presentation edge (or shows all with --all).

    val findings = buildFindings(cycles, propagators, surface)

    MetricsReport(
      scope = scope match
        case Scope.Packages => "packages"
        case Scope.Files    => "files",
      generatedAt = generatedAt,
      summary = Summary(
        nodes = sg.nodes.size,
        edges = sg.edges.size,
        nodesInCycles = cycleSets.map(_.size).sum,
        orphans = orphans.size,
        criticalPathLength = criticalPathLength(sg)
      ),
      cycles = cycles,
      propagators = propagators,
      surface = surface,
      orphans = orphans,
      findings = findings
    )

  private case class RankedFinding(finding: Finding, score: Double)

  /** Builds stable diagnostics from the already-derived index rows. Findings
    * deliberately carry no new graph computation: their score is only used to
    * rank findings within a severity band and is not serialized. */
  private def buildFindings(
      cycles: Seq[Cycle],
      propagators: Seq[PropagatorRow],
      surface: Seq[SurfaceRow]
  ): Seq[Finding] =
    val cycleFindings = cycles.map { cycle =>
      RankedFinding(
        Finding(
          id = s"cycle:${cycle.id}",
          kind = "cycle",
          severity = if cycle.size >= 10 then "critical" else "high",
          subject = cycle.id,
          evidence = s"size=${cycle.size}, extFanIn=${cycle.extFanIn}, greedyCutEstimate=${cycle.cutAnalysis.greedyCutEstimate.getOrElse("none")}",
          confidence = "high",
          nextAction = s"inspect-cycle ${cycle.id}"
        ),
        cycle.size.toDouble + cycle.extFanIn.toDouble / 1000.0
      )
    }
    val propagatorFindings = propagators.map { row =>
      RankedFinding(
        Finding(
          id = s"propagator:${row.node}",
          kind = "propagator",
          severity = if row.score >= 2.0 then "high" else "medium",
          subject = row.node,
          evidence = s"fanIn=${row.fanIn}, fanOut=${row.fanOut}, score=${formatScore(row.score)}",
          confidence = "high",
          nextAction = s"inspect-node ${row.node}"
        ),
        row.score
      )
    }
    val mutableSurfaceFindings = surface.filter(_.mutPorts > 0.0).map { row =>
      RankedFinding(
        Finding(
          id = s"mutableSurface:${row.node}",
          kind = "mutableSurface",
          severity = "high",
          subject = row.node,
          evidence = s"mutPorts=${formatNumber(row.mutPorts)}, exposure=${formatNumber(row.exposure)}",
          confidence = "high",
          nextAction = s"inspect-node ${row.node}"
        ),
        row.exposure
      )
    }
    val structuralUseFindings = surface.flatMap { row =>
      row.utilization.filter(_ < 1.0).map { utilization =>
        RankedFinding(
          Finding(
            id = s"structuralUse:${row.node}",
            kind = "structuralUse",
            severity = "low",
            subject = row.node,
            evidence = s"fanIn=${row.fanIn}, ports=${formatNumber(row.ports)}, utilization=${formatNumber(utilization)}",
            confidence = "structuralProxy",
            nextAction = s"inspect-node ${row.node}"
          ),
          1.0 - utilization
        )
      }
    }
    (cycleFindings ++ propagatorFindings ++ mutableSurfaceFindings ++ structuralUseFindings)
      .sortBy(r => (severityRank(r.finding.severity), -r.score, r.finding.id))
      .map(_.finding)

  private def severityRank(severity: String): Int = severity match
    case "critical" => 0
    case "high"     => 1
    case "medium"   => 2
    case "low"      => 3
    case _           => 4

  private def formatScore(value: Double): String = f"$value%.4f"

  private def formatNumber(value: Double): String =
    if !value.isNaN && !value.isInfinite && value == math.rint(value) then value.toLong.toString
    else value.toString

  /** Longest path (in number of edges) through the condensation DAG: collapse each
    * SCC to a single node, drop the self-loops the collapse creates, then relax in
    * topological order. Acyclic by construction. */
  private def criticalPathLength(sg: ScopeGraph): Int =
    val comps = TarjanScc.components(sg.nodes, sg.edges)
    val compOf = comps.iterator.flatMap(c => c.iterator.map(m => m -> c.min)).toMap
    val dagEdges = sg.edges.iterator
      .map(e => (compOf(e.source), compOf(e.target)))
      .filter { case (s, t) => s != t }
      .toSet
    val compIds = comps.map(_.min).toSet
    val outMap = dagEdges.groupMap(_._1)(_._2)
    val inDeg = scala.collection.mutable.Map.from(dagEdges.groupMapReduce(_._2)(_ => 1)(_ + _))
    val dist = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val ready = scala.collection.mutable.SortedSet.from(compIds.filter(id => inDeg.getOrElse(id, 0) == 0))
    while ready.nonEmpty do
      val u = ready.head
      ready -= u
      // Set.empty (not Nil): the Set + List LUB breaks Ordering inference in Scala 3.7
      for v <- outMap.getOrElse(u, Set.empty).toSeq.sorted do
        if dist(v) < dist(u) + 1 then dist(v) = dist(u) + 1
        inDeg(v) = inDeg(v) - 1
        if inDeg(v) == 0 then ready += v
    dist.values.maxOption.getOrElse(0)

  // ---------- cycles ----------

  private def cycleRow(
      scc: Set[String],
      sg: ScopeGraph,
      cutAnalysisBudget: Option[CutAnalysisBudget]
  ): Cycle =
    val extFanIn = sg.edges.count(e => scc.contains(e.target) && !scc.contains(e.source))
    val internalEdges = sg.edges.count(e => scc.contains(e.source) && scc.contains(e.target))
    val outgoingEdges = sg.edges.count(e => scc.contains(e.source) && !scc.contains(e.target))
    val cutAnalysis = cutAnalysisBudget match
      case Some(budget) => CutAnalyzer.analyze(scc, sg.edges, budget)
      case None         => CutAnalysis.notRequested
    Cycle(
      id = "scc:" + scc.min, // stable key: min member id, NOT a counter
      members = scc.toSeq.sorted,
      size = scc.size,
      extFanIn = extFanIn,
      cutAnalysis = cutAnalysis,
      internalEdges = internalEdges,
      witnessCycle = cyclePath(scc, sg),
      incomingEdges = extFanIn,
      outgoingEdges = outgoingEdges
    )

  /** A simple cycle through the SCC's smallest member as a closed path (first
    * node repeated at the end). Deterministic: DFS from the min member over
    * sorted adjacency; the first edge back to the start closes the path. Every
    * SCC with >1 member contains such a cycle. Defensive fallback (unreachable
    * for a true SCC): sorted members plus the start. */
  private def cyclePath(scc: Set[String], sg: ScopeGraph): Seq[String] =
    val start = scc.min
    val adj = sg.edges.toSeq
      .filter(e => scc.contains(e.source) && scc.contains(e.target))
      .groupMap(_.source)(_.target)
      .view.mapValues(_.toSeq.sorted).toMap
    val visited = mutable.Set.empty[String]
    val path = mutable.ArrayDeque(start)
    var found = false
    def dfs(v: String): Unit =
      if !found then
        visited += v
        for w <- adj.getOrElse(v, Nil) if !found do
          if w == start && path.size >= 2 then
            path.append(start)
            found = true
          else if !visited.contains(w) then
            path.append(w)
            dfs(w)
            if !found then path.removeLast()
    dfs(start)
    if found then path.toSeq else scc.toSeq.sorted :+ start
