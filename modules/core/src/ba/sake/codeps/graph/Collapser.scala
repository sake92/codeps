package ba.sake.codeps.graph

import ba.sake.codeps.model.*

object Collapser:
  /**
    * Maps all nodes and edges through the collapse rules.
    * Longest prefix wins; on ties the first rule in the sequence wins.
    * Loops created by collapsing are dropped; edges landing on the same
    * pair are merged with summed weights.
    */
  def collapse(nodes: Set[String], edges: Set[Edge], rules: Seq[CollapseRule]): (Set[String], Set[Edge]) =
    if rules.isEmpty then (nodes, edges)
    else
      val resolve = resolveWith(rules)
      val newNodes = nodes.map(resolve)
      // toSeq: Set would dedup identical ((s,t), weight) tuples before summing
      val newEdges = edges.toSeq
        .map(e => ((resolve(e.source), resolve(e.target)), e.weight))
        .filter { case ((s, t), _) => s != t }
        .groupMapReduce(_._1)(_._2)(_ + _)
        .map { case ((s, t), w) => Edge(s, t, w) }
        .toSet
      (newNodes, newEdges)

  def resolveWith(rules: Seq[CollapseRule]): String => String =
    pkg =>
      var best: Option[String] = None
      var bestLen              = -1
      for rule <- rules do
        rule(pkg).foreach { result =>
          if rule.prefixLength > bestLen then
            bestLen = rule.prefixLength
            best = Some(result)
        }
      best.getOrElse(pkg)
