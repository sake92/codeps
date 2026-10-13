package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class CycleInspection(id: String, members: Seq[String], witnessCycle: Seq[String], size: Long, extFanIn: Long, internalEdges: Long, incomingEdges: Long, outgoingEdges: Long, cutAnalysis: Artifact__cutAnalysis, findings: Seq[Artifact__finding]) derives JsonRW
object CycleInspection { given Validator[CycleInspection] = Validator.derived[CycleInspection].min(_.size, 2L).min(_.extFanIn, 0L).min(_.internalEdges, 0L).min(_.incomingEdges, 0L).min(_.outgoingEdges, 0L) }