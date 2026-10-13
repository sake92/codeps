package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class HealthScore(score: Double, status: "critical" | "unhealthy" | "needs-attention" | "healthy" | "excellent", factors: HealthFactors) derives JsonRW
object HealthScore { given Validator[HealthScore] = Validator.derived[HealthScore].min(_.score, 1.0d).max(_.score, 10.0d) }