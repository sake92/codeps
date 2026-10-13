package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class HealthFindings(critical: Int, high: Int, medium: Int, low: Int) derives JsonRW
object HealthFindings { given Validator[HealthFindings] = Validator.derived[HealthFindings].min(_.critical, 0).max(_.critical, 2147483647).min(_.high, 0).max(_.high, 2147483647).min(_.medium, 0).max(_.medium, 2147483647).min(_.low, 0).max(_.low, 2147483647) }