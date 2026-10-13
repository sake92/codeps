package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__surface(node: String, fanIn: Long, fanOut: Long, ports: Double, mutPorts: Double, exposure: Double, publicSurface: Double, protectedSurface: Double, packageSurface: Double, privateSurface: Double, publicMutableSurface: Double, protectedMutableSurface: Double, packageMutableSurface: Double, privateMutableSurface: Double, totalDeclaredSurface: Double, dependentsPerPublicPort: Option[Double], cycleId: Option[String], encapsulationRatio: Option[Double], publicMutableRatio: Option[Double]) derives JsonRW
object Artifact__surface { given Validator[Artifact__surface] = Validator.derived[Artifact__surface].min(_.fanIn, 0L).min(_.fanOut, 0L).min(_.ports, 0.0d).min(_.mutPorts, 0.0d).min(_.exposure, 0.0d).min(_.publicSurface, 0.0d).min(_.protectedSurface, 0.0d).min(_.packageSurface, 0.0d).min(_.privateSurface, 0.0d).min(_.publicMutableSurface, 0.0d).min(_.protectedMutableSurface, 0.0d).min(_.packageMutableSurface, 0.0d).min(_.privateMutableSurface, 0.0d).min(_.totalDeclaredSurface, 0.0d) }