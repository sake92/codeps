package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class HealthStructure(nodes: Int, edges: Int) derives JsonRW
object HealthStructure { given Validator[HealthStructure] = Validator.derived[HealthStructure].min(_.nodes, 0).max(_.nodes, 2147483647).min(_.edges, 0).max(_.edges, 2147483647) }