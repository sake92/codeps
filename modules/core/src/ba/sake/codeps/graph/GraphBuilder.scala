package ba.sake.codeps.graph

import ba.sake.codeps.model.*
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}

object GraphBuilder:
  /** Builds a simple directed graph; isolated vertices are kept as nodes. */
  def build(nodes: Set[String], edges: Set[Edge]): DefaultDirectedGraph[String, DefaultEdge] =
    val g = new DefaultDirectedGraph[String, DefaultEdge](classOf[DefaultEdge])
    nodes.foreach(g.addVertex)
    for e <- edges if e.source != e.target do
      g.addVertex(e.source)
      g.addVertex(e.target)
      g.addEdge(e.source, e.target) // returns null on duplicate; fine
    g
