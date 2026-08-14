package ba.sake.codeps.graph

import ba.sake.codeps.model.*

object Collapser:
  /**
    * Maps all nodes and edges through the collapse rules.
    * Longest prefix wins; on ties the first rule in the sequence wins.
    * Loops created by collapsing are dropped; edges deduplicate via Set semantics.
    */
  def collapse(nodes: Set[String], edges: Set[Edge], rules: Seq[CollapseRule]): (Set[String], Set[Edge]) =
    if rules.isEmpty then (nodes, edges)
    else
      val resolve = resolveWith(rules)
      val newNodes = nodes.map(resolve)
      val newEdges = edges
        .map(e => Edge(resolve(e.source), resolve(e.target)))
        .filter(e => e.source != e.target)
      (newNodes, newEdges)

  private def resolveWith(rules: Seq[CollapseRule]): String => String =
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
