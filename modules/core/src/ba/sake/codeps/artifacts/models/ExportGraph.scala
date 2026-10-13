package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class ExportGraph(packages: (nodes: Seq[Artifact__packageNode], edges: Seq[Artifact__edge]), files: (nodes: Seq[Artifact__fileNode], edges: Seq[Artifact__edge])) derives JsonRW