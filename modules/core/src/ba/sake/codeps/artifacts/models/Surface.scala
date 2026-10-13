package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Surface(node: String, fanIn: Int, fanOut: Int, ports: Double, mutPorts: Double, exposure: Double, publicSurface: Double, protectedSurface: Double, packageSurface: Double, privateSurface: Double, publicMutableSurface: Double, protectedMutableSurface: Double, packageMutableSurface: Double, privateMutableSurface: Double, totalDeclaredSurface: Double, dependentsPerPublicPort: Option[Double], cycleId: Option[String], encapsulationRatio: Option[Double], publicMutableRatio: Option[Double]) derives JsonRW
object Surface { given Validator[Surface] = Validator.derived[Surface].min(_.fanIn, 0).max(_.fanIn, 2147483647).min(_.fanOut, 0).max(_.fanOut, 2147483647).min(_.ports, 0.0d).min(_.mutPorts, 0.0d).min(_.exposure, 0.0d).min(_.publicSurface, 0.0d).min(_.protectedSurface, 0.0d).min(_.packageSurface, 0.0d).min(_.privateSurface, 0.0d).min(_.publicMutableSurface, 0.0d).min(_.protectedMutableSurface, 0.0d).min(_.packageMutableSurface, 0.0d).min(_.privateMutableSurface, 0.0d).min(_.totalDeclaredSurface, 0.0d) }