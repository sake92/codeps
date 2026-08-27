package ba.sake.codeps.report

import ba.sake.codeps.graph.{Collapser, Filter, TarjanScc, TestFilter}
import ba.sake.codeps.model.*

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
    val knotSets = TarjanScc.knots(sg.nodes, sg.edges)
    val knots = knotSets.map(knot => knotRow(knot, sg)).sortBy(k => (-k.size, -k.extFanIn, k.id))

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
      generatedAt = java.time.OffsetDateTime.now().toString,
      summary = Summary(
        nodes = sg.nodes.size,
        edges = sg.edges.size,
        nodesInCycles = knotSets.map(_.size).sum,
        orphans = orphans.size,
        criticalPathLength = criticalPathLength(sg)
      ),
      knots = knots,
      surface = surface,
      orphans = orphans,
      articulationPoints = articulationPoints(sg)
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

  /** Nodes whose removal increases the number of connected components of the
    * undirected view of the graph (DFS with discovery/low-link times — same
    * family as Tarjan, on the undirected graph). */
  private def articulationPoints(sg: ScopeGraph): Seq[String] =
    val adjacency = sg.edges.toSeq
      .flatMap(e => Seq((e.source, e.target), (e.target, e.source)))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct.sorted)
      .toMap
    val disc = scala.collection.mutable.Map.empty[String, Int]
    val low = scala.collection.mutable.Map.empty[String, Int]
    val result = scala.collection.mutable.Set.empty[String]
    var time = 0

    def dfs(u: String, parent: Option[String]): Unit =
      disc(u) = time
      low(u) = time
      time += 1
      var children = 0
      for v <- adjacency.getOrElse(u, Nil) do
        if parent.forall(_ != v) then
          if !disc.contains(v) then
            children += 1
            dfs(v, Some(u))
            low(u) = math.min(low(u), low(v))
            if parent.isEmpty then
              if children > 1 then result += u
            else if low(v) >= disc(u) then result += u
          else
            low(u) = math.min(low(u), disc(v))

    for u <- sg.nodes.toSeq.sorted do
      if !disc.contains(u) then dfs(u, None)
    result.toSeq.sorted

  // ---------- knots ----------

  /** Max candidate edges tested per knot per simulation round. */
  private val maxCutCandidates = 6

  private def knotRow(knot: Set[String], sg: ScopeGraph): Knot =
    val members = knot.toSeq.sorted
    val extFanIn = sg.edges.count(e => knot.contains(e.target) && !knot.contains(e.source))
    Knot(
      id = "scc:" + members.head, // stable key: min member id, NOT a counter
      members = members,
      size = members.size,
      extFanIn = extFanIn,
      minCutsEstimate = minCutsEstimate(knot, sg),
      cutCandidates = cutCandidates(knot, sg)
    )

  /** Internal edges of the knot sorted by weight ascending (stable tiebreak),
    * top N, each with its simulated effect. */
  private def cutCandidates(knot: Set[String], sg: ScopeGraph): Seq[CutCandidate] =
    sg.edges.toSeq
      .filter(e => knot.contains(e.source) && knot.contains(e.target))
      .sortBy(e => (e.weight, e.source, e.target))
      .take(maxCutCandidates)
      .map(e => simulateCut(e, knot, sg.edges))

  /** Removes the edge from a copy of the edge list, reruns Tarjan, and classifies
    * the effect on the component(s) containing the edge's endpoints:
    * - no multi-member component left -> "resolved"
    * - a multi-member component remains but is smaller -> "partial" (report the largest)
    * - same size (endpoints still in one component) -> "none": the edge is redundant
    *   with the rest of the cycle (e.g. a chord running the ring's direction). */
  private def simulateCut(e: Edge, knot: Set[String], edges: Set[Edge]): CutCandidate =
    val containing = TarjanScc.components(knot, edges - e)
      .filter(c => c.contains(e.source) || c.contains(e.target))
    val multiSizes = containing.map(_.size).filter(_ >= 2)
    val (effect, newSize) =
      if multiSizes.isEmpty then ("resolved", 1)
      else if multiSizes.max < knot.size then ("partial", multiSizes.max)
      else ("none", knot.size)
    CutCandidate(e.source, e.target, e.weight, effect, newSize)

  /** Greedy estimate of the cuts needed to dissolve the knot: repeatedly apply the
    * best candidate (resolved wins; else the partial with the smallest remaining
    * size), recompute the SCCs on the mutated trial edge list, repeat against the
    * shrunk component until it reaches size 1 or nothing improves. A greedy
    * heuristic, NOT a guaranteed-minimum feedback-edge set. */
  private def minCutsEstimate(knot: Set[String], sg: ScopeGraph): Int =
    var trialEdges = sg.edges
    var comp = knot
    var cuts = 0
    var done = false
    while comp.size > 1 && !done do
      val candidates = trialEdges.toSeq
        .filter(e => comp.contains(e.source) && comp.contains(e.target))
        .sortBy(e => (e.weight, e.source, e.target))
        .take(maxCutCandidates)
        .map(e => (e, simulateCut(e, comp, trialEdges)))
      val improved = candidates.filter { case (_, c) => c.effect != "none" }
      if improved.isEmpty then done = true
      else
        val (chosen, chosenResult) = improved.minBy { case (e, c) =>
          (if c.effect == "resolved" then 0 else 1, c.newSize, e.weight, e.source, e.target)
        }
        trialEdges = trialEdges - chosen
        cuts += 1
        if chosenResult.effect == "resolved" then comp = Set(comp.min)
        else
          comp = TarjanScc.components(comp, trialEdges)
            .filter(c => (c.contains(chosen.source) || c.contains(chosen.target)) && c.size >= 2)
            .maxBy(c => (c.size, c.min))
    cuts
