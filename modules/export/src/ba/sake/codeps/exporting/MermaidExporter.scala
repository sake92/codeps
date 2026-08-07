package ba.sake.codeps.exporting

import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import scala.jdk.CollectionConverters.*

object MermaidExporter:
  /** Aliased node ids (N0, N1, ...) because mermaid ids break on dots. */
  def render(g: DefaultDirectedGraph[String, DefaultEdge]): String =
    val nodes   = g.vertexSet().asScala.toSeq.sorted
    val aliases = nodes.zipWithIndex.map((n, i) => n -> s"N$i").toMap
    val sb = new StringBuilder
    sb.append("flowchart LR\n")
    for n <- nodes do
      sb.append(s"""  ${aliases(n)}["$n"]\n""")
    for e <- g.edgeSet().asScala.toSeq.sortBy(e => (g.getEdgeSource(e), g.getEdgeTarget(e))) do
      sb.append(s"  ${aliases(g.getEdgeSource(e))} --> ${aliases(g.getEdgeTarget(e))}\n")
    sb.toString
