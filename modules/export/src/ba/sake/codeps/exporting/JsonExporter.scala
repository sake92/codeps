package ba.sake.codeps.exporting

import ba.sake.codeps.model.PkgStats
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import scala.jdk.CollectionConverters.*

object JsonExporter:
  def render(g: DefaultDirectedGraph[String, DefaultEdge], counts: Map[String, PkgStats] = Map.empty): String =
    val nodes = g.vertexSet().asScala.toSeq.sorted
    val edges = g.edgeSet().asScala.toSeq
      .map(e => Seq(g.getEdgeSource(e), g.getEdgeTarget(e)))
      .sortBy(_.mkString)
    val nodesJson = nodes.map(n => s""""$n"""").mkString(", ")
    val edgesJson = edges.map(pair => pair.map(p => s""""$p"""").mkString(", ")).map(p => s"[$p]").mkString(", ")
    val infoJson =
      if counts.isEmpty then ""
      else
        val entries = nodes.filter(counts.contains).map { n =>
          val s = counts(n)
          s"""    "$n": {"files": ${s.fileCount}, "classes": ${s.classCount}}"""
        }
        if entries.isEmpty then "" else ",\n  \"nodeInfo\": {\n" + entries.mkString(",\n") + "\n  }"
    s"""{
       |  "nodes": [$nodesJson],
       |  "edges": [$edgesJson]$infoJson
       |}
       |""".stripMargin
