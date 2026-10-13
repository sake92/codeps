package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class HealthFactors(cycles: Double, mutableSurface: Double, exposedSurface: Double, structuralUse: Double, propagators: Double) derives JsonRW
object HealthFactors { given Validator[HealthFactors] = Validator.derived[HealthFactors].min(_.cycles, 0.0d).max(_.cycles, 10.0d).min(_.mutableSurface, 0.0d).max(_.mutableSurface, 10.0d).min(_.exposedSurface, 0.0d).max(_.exposedSurface, 10.0d).min(_.structuralUse, 0.0d).max(_.structuralUse, 10.0d).min(_.propagators, 0.0d).max(_.propagators, 10.0d) }