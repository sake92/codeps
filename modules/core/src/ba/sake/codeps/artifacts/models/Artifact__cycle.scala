package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__cycle(id: String, members: Seq[String], witnessCycle: Seq[String], size: Long, extFanIn: Long, internalEdges: Long, incomingEdges: Long, outgoingEdges: Long, cutAnalysis: Artifact__cutAnalysis) derives JsonRW
object Artifact__cycle { given Validator[Artifact__cycle] = Validator.derived[Artifact__cycle].min(_.size, 2L).min(_.extFanIn, 0L).min(_.internalEdges, 0L).min(_.incomingEdges, 0L).min(_.outgoingEdges, 0L) }