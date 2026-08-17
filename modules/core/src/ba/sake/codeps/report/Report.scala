package ba.sake.codeps.report

import ba.sake.codeps.model.{DepsGraph, Edge}
import ba.sake.tupson.JsonRW

/** One reported cycle: the members in true dependency order (["a","c","b","a"] = a -> c -> b -> a),
  * the edges of the cycle, its grade, and the member whose removal is least costly (lowest degree).
  */
case class Cycle(
    members: Seq[String],
    edges: Seq[Edge],
    severity: Severity,
    breakCandidate: String
) derives JsonRW

/** Per-node degree metrics (ported from the demo page's hub-score logic). */
case class Metrics(in: Int, out: Int, hub: Int) derives JsonRW

/** An edge that participates in a cycle, ranked by how many reported cycles contain it. */
case class BreakEdge(edge: Edge, breaks: Int) derives JsonRW

/** Actionable suggestions: edges to consider cutting, plus the hardest knots / easy wins
  * (hub = in*out, benefit = out-in) — only filled at the package level.
  */
case class Suggestions(
    breakEdges: Seq[BreakEdge],
    hardestKnots: Seq[String],
    easyWins: Seq[String]
) derives JsonRW

/** Analysis of one granularity level. `graph` and `metrics` are only present at
  * package/file/type level — the member-level graph is too large to embed usefully.
  */
case class LevelReport(
    graph: Option[DepsGraph],
    metrics: Option[Map[String, Metrics]],
    cycles: Seq[Cycle],
    suggestions: Suggestions
) derives JsonRW

/** The full analysis report: one LevelReport per granularity, keyed by lowercase level name
  * ("package", "file", "type", "member"). Self-contained — feeds both agents and the demo page.
  */
case class AnalysisReport(levels: Map[String, LevelReport]) derives JsonRW
