package ba.sake.codeps.exporting

import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import scala.jdk.CollectionConverters.*

object JsonExporter:
  def render(g: DefaultDirectedGraph[String, DefaultEdge]): String =
    val nodes = g.vertexSet().asScala.toSeq.sorted
    val edges = g.edgeSet().asScala.toSeq
      .map(e => Seq(g.getEdgeSource(e), g.getEdgeTarget(e)))
      .sortBy(_.mkString)
    val nodesJson = nodes.map(n => s""""$n"""").mkString(", ")
    val edgesJson = edges.map(pair => pair.map(p => s""""$p"""").mkString(", ")).map(p => s"[$p]").mkString(", ")
    s"""{
       |  "nodes": [$nodesJson],
       |  "edges": [$edgesJson]
       |}
       |""".stripMargin
