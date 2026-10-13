package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Shared codeps artifact definitions__packageNode`(id: String, ports: Double, mutPorts: Double, declarationSurface: `Shared codeps artifact definitions__declarationSurface`) derives JsonRW
object `Shared codeps artifact definitions__packageNode` { given Validator[`Shared codeps artifact definitions__packageNode`] = Validator.derived[`Shared codeps artifact definitions__packageNode`].min(_.ports, 0.0d).min(_.mutPorts, 0.0d) }