package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class HealthCycles(count: Int, nodes: Int, largestScc: Int, internalEdges: Int) derives JsonRW
object HealthCycles { given Validator[HealthCycles] = Validator.derived[HealthCycles].min(_.count, 0).max(_.count, 2147483647).min(_.nodes, 0).max(_.nodes, 2147483647).min(_.largestScc, 0).max(_.largestScc, 2147483647).min(_.internalEdges, 0).max(_.internalEdges, 2147483647) }