package ba.sake.codeps.graph

import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import scala.jdk.CollectionConverters.*

object CycleDetector:
  /**
    * Finds cycles as strongly connected components of size >= 2
    * (self-loops are dropped earlier, so singletons are never cycles).
    *
    * For each component, one representative elementary cycle is extracted in
    * true dependency order: ["a", "c", "b", "a"] means a -> c -> b -> a.
    * Output is deterministic: every cycle is rotated to start from its
    * lexicographically smallest member, and cycles are sorted by that member.
    *
    * Note: a strongly connected component containing several cycles
    * (e.g. a <-> b <-> c) reports a single representative cycle, which may
    * not include every member of the component.
    */
  def detect(g: DefaultDirectedGraph[String, DefaultEdge]): Seq[Seq[String]] =
    val sccs = new KosarajuStrongConnectivityInspector(g).stronglyConnectedSets().asScala
      .toSeq
      .map(_.asScala.toSeq)
      .filter(_.size >= 2)
    sccs.map(findCycle(g, _)).sortBy(_.head)

  /**
    * Walks a single path through the component, always extending to the
    * smallest unvisited member. The walk never backtracks, so every visited
    * member is on the path; strong connectivity guarantees the last node has
    * an out-neighbor inside the component, and all of them are on the path,
    * so the walk always closes a cycle. The back edge to the smallest path
    * index is chosen, giving the longest cycle.
    */
  private def findCycle(g: DefaultDirectedGraph[String, DefaultEdge], scc: Seq[String]): Seq[String] =
    val members = scc.toSet
    var path    = Seq(scc.min)
    var onPath  = Set(scc.min)
    var cycle: Option[Seq[String]] = None
    while cycle.isEmpty do
      val outs = g.outgoingEdgesOf(path.last).asScala.toSeq
        .map(g.getEdgeTarget)
        .filter(members.contains)
        .distinct
        .sorted
      outs.find(!onPath(_)) match
        case Some(next) =>
          path = path :+ next
          onPath = onPath + next
        case None =>
          val back = outs.minBy(path.indexOf)
          cycle = Some(rotateMinFirst(path.drop(path.indexOf(back)) :+ back))
    cycle.get

  /** Rotates a closed loop so its smallest (distinct) member comes first. */
  private def rotateMinFirst(cycle: Seq[String]): Seq[String] =
    val body     = cycle.init
    val idx      = body.indexOf(body.min)
    val rotated = body.drop(idx) ++ body.take(idx)
    rotated :+ rotated.head
