package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class HealthSurface(publicSurface: Double, publicMutableSurface: Double, totalDeclaredSurface: Double, encapsulationRatio: Option[Double]) derives JsonRW
object HealthSurface { given Validator[HealthSurface] = Validator.derived[HealthSurface].min(_.publicSurface, 0.0d).min(_.publicMutableSurface, 0.0d).min(_.totalDeclaredSurface, 0.0d) }