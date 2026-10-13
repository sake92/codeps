package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__packageNode(id: String, ports: Double, mutPorts: Double, declarationSurface: Artifact__declarationSurface) derives JsonRW
object Artifact__packageNode { given Validator[Artifact__packageNode] = Validator.derived[Artifact__packageNode].min(_.ports, 0.0d).min(_.mutPorts, 0.0d) }