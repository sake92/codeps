package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Cycle(id: String, members: Seq[String], witnessCycle: Seq[String], size: Int, extFanIn: Int, internalEdges: Int, incomingEdges: Int, outgoingEdges: Int, cutAnalysis: CutAnalysis) derives JsonRW
object Cycle { given Validator[Cycle] = Validator.derived[Cycle].min(_.size, 2).max(_.size, 2147483647).min(_.extFanIn, 0).max(_.extFanIn, 2147483647).min(_.internalEdges, 0).max(_.internalEdges, 2147483647).min(_.incomingEdges, 0).max(_.incomingEdges, 2147483647).min(_.outgoingEdges, 0).max(_.outgoingEdges, 2147483647) }