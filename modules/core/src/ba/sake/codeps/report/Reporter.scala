package ba.sake.codeps.report

import ba.sake.codeps.graph.{Aggregator, Collapser, CycleDetector, Filter, GraphBuilder, TestFilter}
import ba.sake.codeps.model.*
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import scala.jdk.CollectionConverters.*

/** Runs the analysis pipeline (filter -> optional test-filter -> aggregate -> collapse -> graph -> cycles) at
  * every granularity in one pass, grades each cycle, and computes per-level metrics and suggestions.
  */
object Reporter:

  /** Levels analyzed, coarsest first. */
  val levels: Seq[Aggregator.Level] =
    Seq(Aggregator.Level.Package, Aggregator.Level.File, Aggregator.Level.Type, Aggregator.Level.Member)

  /** Lowercase JSON key of a level ("package", "file", "type", "member"). */
  def levelKey(level: Aggregator.Level): String = level.toString.toLowerCase

  def run(
      graph: DepsGraph,
      include: Seq[String],
      exclude: Seq[String],
      rules: Seq[CollapseRule],
      /** Patterns matched against node file paths; matching test nodes are dropped before
        * aggregation (see `TestFilter`). `None` skips the test filter. */
      testPatterns: Option[Seq[String]] = None
  ): AnalysisReport =
    val includeExcludeFiltered = Filter(graph, include, exclude)
    val filtered = testPatterns match
      case Some(patterns) => TestFilter.skipTests(includeExcludeFiltered, patterns)
      case None           => includeExcludeFiltered
    AnalysisReport(levels.map(l => levelKey(l) -> levelReport(filtered, l, rules)).toMap)

  private def levelReport(graph: DepsGraph, level: Aggregator.Level, rules: Seq[CollapseRule]): LevelReport =
    val (ids, edges) = Aggregator.aggregate(graph, level)
    val (collapsedIds, collapsedEdges) = Collapser.collapse(ids, edges, rules)
    val g = GraphBuilder.build(collapsedIds, collapsedEdges)
    val cycles = CycleDetector.detect(g).map { c =>
      Cycle(c, cycleEdges(c), grade(level, c, ids, graph), breakCandidate(c, g))
    }
    val withGraph = level != Aggregator.Level.Member
    LevelReport(
      graph = if withGraph then Some(graphAt(level, ids, collapsedIds, collapsedEdges, graph)) else None,
      metrics = if withGraph then Some(metricsOf(g)) else None,
      cycles = cycles,
      suggestions = suggestionsOf(level, cycles, g)
    )

  // ---------- grading ----------

  /** package cycles are always bad; file cycles are meh. type/member cycles are fine only
    * when every member resolves to a node in the same file — cross-file, file-less (jdeps)
    * and collapse-rewritten members are graded conservatively meh.
    */
  private def grade(level: Aggregator.Level, cycle: Seq[String], aggregatedIds: Set[String], graph: DepsGraph): Severity =
    level match
      case Aggregator.Level.Package => Severity.bad
      case Aggregator.Level.File    => Severity.meh
      case Aggregator.Level.Type | Aggregator.Level.Member =>
        val nodesById = graph.nodes.map(n => n.id -> n).toMap
        val memberNodes = cycle.distinct.map(id => nodesById.get(id).filter(n => aggregatedIds.contains(n.id)))
        if memberNodes.forall(_.isDefined) then
          val files = memberNodes.map(_.get.file)
          if files.forall(_.isDefined) && files.map(_.get).distinct.size == 1 then Severity.fine
          else Severity.meh
        else Severity.meh

  // ---------- metrics / suggestions ----------

  private def metricsOf(g: DefaultDirectedGraph[String, DefaultEdge]): Map[String, Metrics] =
    g.vertexSet().asScala.map { id =>
      val in  = g.inDegreeOf(id)
      val out = g.outDegreeOf(id)
      id -> Metrics(in, out, in * out)
    }.toMap

  private def suggestionsOf(
      level: Aggregator.Level,
      cycles: Seq[Cycle],
      g: DefaultDirectedGraph[String, DefaultEdge]
  ): Suggestions =
    val breakEdges = cycles
      .flatMap(_.edges)
      .groupBy(identity)
      .map((e, es) => BreakEdge(e, es.size))
      .toSeq
      .sortBy(be => (-be.breaks, be.edge.source, be.edge.target))
    if level == Aggregator.Level.Package then
      val inOut = g.vertexSet().asScala.map(id => id -> (g.inDegreeOf(id), g.outDegreeOf(id))).toMap
      val knots = inOut.collect { case (id, (in, out)) if in * out > 0 => id -> (in * out) }
        .toSeq.sortBy { case (_, hub) => -hub }.map(_._1).take(5)
      val easy = inOut.collect { case (id, (in, out)) if out > 0 => id -> (out, in) }
        .toSeq.sortBy { case (_, (out, in)) => (-out, in) }.map(_._1).take(5)
      Suggestions(breakEdges, knots, easy)
    else Suggestions(breakEdges, Seq.empty, Seq.empty)

  // ---------- helpers ----------

  private def cycleEdges(cycle: Seq[String]): Seq[Edge] =
    cycle.sliding(2).map { case Seq(a, b) => Edge(a, b) }.toSeq

  /** Member whose removal is least costly: lowest total degree in the graph, ties by id. */
  private def breakCandidate(cycle: Seq[String], g: DefaultDirectedGraph[String, DefaultEdge]): String =
    cycle.distinct.minBy(id => (g.degreeOf(id), id))

  private def graphAt(
      level: Aggregator.Level,
      aggregatedIds: Set[String],
      collapsedIds: Set[String],
      collapsedEdges: Set[Edge],
      graph: DepsGraph
  ): DepsGraph =
    val originalNodes = graph.nodes.map(n => n.id -> n).toMap
    val fallbackKind = level match
      case Aggregator.Level.Package => NodeKind.`package`
      case Aggregator.Level.File    => NodeKind.file
      case Aggregator.Level.Type    => NodeKind.`type`
      case Aggregator.Level.Member  => NodeKind.member
    val nodes = collapsedIds.map { id =>
      originalNodes.get(id) match
        case Some(n) if aggregatedIds.contains(id) => n
        case _                                     => Node(id, fallbackKind)
    }
    DepsGraph(nodes, collapsedEdges)
