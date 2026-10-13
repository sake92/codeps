package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Shared codeps artifact definitions__summary`(nodes: Long, edges: Long, nodesInCycles: Long, orphans: Long) derives JsonRW
object `Shared codeps artifact definitions__summary` { given Validator[`Shared codeps artifact definitions__summary`] = Validator.derived[`Shared codeps artifact definitions__summary`].min(_.nodes, 0L).min(_.edges, 0L).min(_.nodesInCycles, 0L).min(_.orphans, 0L) }