package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class PackageNode(id: String, ports: Double, mutPorts: Double, declarationSurface: DeclarationSurface) derives JsonRW
object PackageNode { given Validator[PackageNode] = Validator.derived[PackageNode].min(_.ports, 0.0d).min(_.mutPorts, 0.0d) }