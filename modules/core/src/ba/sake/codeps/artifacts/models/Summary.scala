package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Summary(nodes: Int, edges: Int, nodesInCycles: Int, orphans: Int) derives JsonRW
object Summary { given Validator[Summary] = Validator.derived[Summary].min(_.nodes, 0).max(_.nodes, 2147483647).min(_.edges, 0).max(_.edges, 2147483647).min(_.nodesInCycles, 0).max(_.nodesInCycles, 2147483647).min(_.orphans, 0).max(_.orphans, 2147483647) }