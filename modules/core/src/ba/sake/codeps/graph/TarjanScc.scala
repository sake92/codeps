package ba.sake.codeps.graph

import ba.sake.codeps.model.Edge
import scala.collection.mutable

/** Tarjan's strongly connected components over a plain node/edge list.
  * The graph is rebuilt from the list on every call — components are always
  * derived fresh, which is what makes the cut simulation correct (it works by
  * removing an edge from a copy of the list and recomputing).
  * Deterministic: adjacency is built from the edge list and neighbors are
  * visited in sorted order, so results are stable across runs and independent
  * of the input sets' iteration order.
  */
object TarjanScc:

  /** All components, including singletons, sorted by their lexicographically
    * smallest member id. */
  def components(nodes: Set[String], edges: Set[Edge]): Seq[Set[String]] =
    val adjacency = edges.toSeq
      .groupMap(_.source)(_.target)
      .view
      .mapValues(_.distinct.sorted)
      .toMap
    val allNodes = (nodes ++ edges.flatMap(e => Seq(e.source, e.target))).toSeq.sorted
    val disc = mutable.Map.empty[String, Int]
    val low = mutable.Map.empty[String, Int]
    val onStack = mutable.Set.empty[String]
    val stack = mutable.ArrayDeque.empty[String]
    val result = mutable.ListBuffer.empty[Set[String]]
    var index = 0

    def strongConnect(v: String): Unit =
      disc(v) = index
      low(v) = index
      index += 1
      stack.append(v)
      onStack += v
      for w <- adjacency.getOrElse(v, Nil) do
        if !disc.contains(w) then
          strongConnect(w)
          low(v) = math.min(low(v), low(w))
        else if onStack.contains(w) then
          low(v) = math.min(low(v), disc(w))
      if low(v) == disc(v) then
        var scc = Set.empty[String]
        var done = false
        while !done do
          val w = stack.removeLast()
          onStack -= w
          scc += w
          if w == v then done = true
        result += scc

    for v <- allNodes do
      if !disc.contains(v) then strongConnect(v)
    result.toSeq.sortBy(_.min)

  /** Only components with more than 1 member — singleton components are just
    * acyclic nodes and never count as cycles. */
  def cycles(nodes: Set[String], edges: Set[Edge]): Seq[Set[String]] =
    components(nodes, edges).filter(_.size > 1)
