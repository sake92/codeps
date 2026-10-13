package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__fileNode(id: String, packageId: Option[String], ports: Double, mutPorts: Double, declarationSurface: Artifact__declarationSurface) derives JsonRW
object Artifact__fileNode { given Validator[Artifact__fileNode] = Validator.derived[Artifact__fileNode].min(_.ports, 0.0d).min(_.mutPorts, 0.0d) }