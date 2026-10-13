package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__summary(nodes: Long, edges: Long, nodesInCycles: Long, orphans: Long) derives JsonRW
object Artifact__summary { given Validator[Artifact__summary] = Validator.derived[Artifact__summary].min(_.nodes, 0L).min(_.edges, 0L).min(_.nodesInCycles, 0L).min(_.orphans, 0L) }