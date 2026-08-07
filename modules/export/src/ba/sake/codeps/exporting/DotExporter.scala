package ba.sake.codeps.exporting

import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import scala.jdk.CollectionConverters.*

object DotExporter:
  def render(g: DefaultDirectedGraph[String, DefaultEdge]): String =
    val sb = new StringBuilder
    sb.append("digraph deps {\n")
    for e <- g.edgeSet().asScala.toSeq.sortBy(e => (g.getEdgeSource(e), g.getEdgeTarget(e))) do
      sb.append(s"""  "${escape(g.getEdgeSource(e))}" -> "${escape(g.getEdgeTarget(e))}";\n""")
    val endpoints = g.edgeSet().asScala.flatMap(e => Seq(g.getEdgeSource(e), g.getEdgeTarget(e))).toSet
    for v <- g.vertexSet().asScala.toSeq.sorted if !endpoints.contains(v) do
      sb.append(s"""  "${escape(v)}";\n""")
    sb.append("}\n")
    sb.toString

  private def escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
