package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Codeps cycle inspection`(id: String, members: Seq[String], witnessCycle: Seq[String], size: Long, extFanIn: Long, internalEdges: Long, incomingEdges: Long, outgoingEdges: Long, cutAnalysis: `Shared codeps artifact definitions__cutAnalysis`, findings: Seq[`Shared codeps artifact definitions__finding`]) derives JsonRW
object `Codeps cycle inspection` { given Validator[`Codeps cycle inspection`] = Validator.derived[`Codeps cycle inspection`].min(_.size, 2L).min(_.extFanIn, 0L).min(_.internalEdges, 0L).min(_.incomingEdges, 0L).min(_.outgoingEdges, 0L) }