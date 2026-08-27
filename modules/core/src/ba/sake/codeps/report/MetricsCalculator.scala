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
      testPatterns: Option[Seq[String]] = None
  ): Either[String, MetricsReport] =
    val filtered = Filter(graph, includes, excludes)
    val scoped = testPatterns match
      case Some(patterns) => TestFilter.skipTests(filtered, patterns)
      case None           => filtered
    scopeGraph(scoped, scope).map { sg => compute(collapse(sg, collapseRules), scope) }

  private def compute(sg: ScopeGraph, scope: Scope): MetricsReport =
    val fanIn = sg.edges.groupMapReduce(_.target)(_ => 1)(_ + _)
    val fanOut = sg.edges.groupMapReduce(_.source)(_ => 1)(_ + _)
    def fanInOf(id: String): Int = fanIn.getOrElse(id, 0)
    def fanOutOf(id: String): Int = fanOut.getOrElse(id, 0)

    val orphans = sg.nodes.filter(n => fanInOf(n) == 0 && fanOutOf(n) == 0).toSeq.sorted
    val cycleSets = TarjanScc.cycles(sg.nodes, sg.edges)
    val cycles = cycleSets.map(k => cycleRow(k, sg)).sortBy(k => (-k.size, -k.extFanIn, k.id))

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
          utilization = if fi > 0 && p > 0 then Some(fi / p) else None
        )
      }
      .sortBy(r => (if r.utilization.isEmpty then 1 else 0, r.utilization.getOrElse(0.0), r.node))

    MetricsReport(
      scope = scope match
        case Scope.Packages => "packages"
        case Scope.Files    => "files",
      generatedAt = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString,
      summary = Summary(
        nodes = sg.nodes.size,
        edges = sg.edges.size,
        nodesInCycles = cycleSets.map(_.size).sum,
        orphans = orphans.size,
        criticalPathLength = criticalPathLength(sg)
      ),
      cycles = cycles,
      surface = surface,
      orphans = orphans
    )

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

  /** Max candidate edges tested per cycle per simulation round. */
  private val maxCutCandidates = 6

  private def cycleRow(scc: Set[String], sg: ScopeGraph): Cycle =
    val extFanIn = sg.edges.count(e => scc.contains(e.target) && !scc.contains(e.source))
    Cycle(
      id = "scc:" + scc.min, // stable key: min member id, NOT a counter
      members = cyclePath(scc, sg),
      size = scc.size,
      extFanIn = extFanIn,
      minCutsEstimate = minCutsEstimate(scc, sg),
      cutCandidates = cutCandidates(scc, sg)
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
        for w <- adj.getOrElse(v, Nil) do
          if found then return
          else if w == start && path.size >= 2 then
            path.append(start)
            found = true
            return
          else if !visited.contains(w) then
            path.append(w)
            dfs(w)
            if !found then path.removeLast()
    dfs(start)
    if found then path.toSeq else scc.toSeq.sorted :+ start

  /** Internal edges whose removal resolves the cycle for their endpoints (they end
    * up in no multi-member component; a leftover cycle elsewhere in the SCC does
    * not count), sorted by weight ascending (stable tiebreak), top N. Every
    * internal edge is simulated — a cheap edge that does not break the cycle is
    * not a recommendation, and a resolving edge is reported even when it is not
    * among the lowest weights. */
  private def cutCandidates(scc: Set[String], sg: ScopeGraph): Seq[CutCandidate] =
    sg.edges.toSeq
      .filter(e => scc.contains(e.source) && scc.contains(e.target))
      .map(e => (e, simulateCut(e, scc, sg.edges)))
      .collect { case (e, ("resolved", _)) => e }
      .sortBy(e => (e.weight, e.source, e.target))
      .take(maxCutCandidates)
      .map(e => CutCandidate(e.source, e.target, e.weight))

  /** Removes the edge from a copy of the edge list, reruns Tarjan, and classifies
    * the effect on the component(s) containing the edge's endpoints:
    * - no multi-member component left -> ("resolved", 1)
    * - a multi-member component remains but is smaller -> ("partial", largest size)
    * - same size (endpoints still in one component) -> ("none", scc.size): the edge
    *   is redundant with the rest of the cycle (e.g. a chord running the ring's direction). */
  private def simulateCut(e: Edge, scc: Set[String], edges: Set[Edge]): (String, Int) =
    val containing = TarjanScc.components(scc, edges - e)
      .filter(c => c.contains(e.source) || c.contains(e.target))
    val multiSizes = containing.map(_.size).filter(_ >= 2)
    if multiSizes.isEmpty then ("resolved", 1)
    else if multiSizes.max < scc.size then ("partial", multiSizes.max)
    else ("none", scc.size)

  /** Greedy estimate of the cuts needed to dissolve the cycle: repeatedly apply the
    * best candidate (resolved wins; else the partial with the smallest remaining
    * size) to the trial edge list, then set `comp` to the largest remaining
    * multi-member component of the original cycle members; repeat while one exists.
    * A greedy heuristic, NOT a guaranteed-minimum feedback-edge set. */
  private def minCutsEstimate(cycle: Set[String], sg: ScopeGraph): Int =
    var trialEdges = sg.edges
    var comp = cycle
    var cuts = 0
    var done = false
    while comp.size > 1 && !done do
      val candidates = trialEdges.toSeq
        .filter(e => comp.contains(e.source) && comp.contains(e.target))
        .sortBy(e => (e.weight, e.source, e.target))
        .take(maxCutCandidates)
        .map(e => (e, simulateCut(e, comp, trialEdges)))
      val improved = candidates.filter { case (_, (effect, _)) => effect != "none" }
      if improved.isEmpty then done = true
      else
        val (chosen, _) = improved.minBy { case (e, (effect, newSize)) =>
          (if effect == "resolved" then 0 else 1, newSize, e.weight, e.source, e.target)
        }
        trialEdges = trialEdges - chosen
        cuts += 1
        comp = TarjanScc.components(comp, trialEdges)
          .filter(_.size >= 2)
          .maxByOption(c => (c.size, c.min))
          .getOrElse(Set.empty)
    cuts
