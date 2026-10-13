package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class FileNode(id: String, packageId: Option[String], ports: Double, mutPorts: Double, declarationSurface: DeclarationSurface) derives JsonRW
object FileNode { given Validator[FileNode] = Validator.derived[FileNode].min(_.ports, 0.0d).min(_.mutPorts, 0.0d) }