package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Shared codeps artifact definitions__propagator`(node: String, fanIn: Long, fanOut: Long, score: Double) derives JsonRW
object `Shared codeps artifact definitions__propagator` { given Validator[`Shared codeps artifact definitions__propagator`] = Validator.derived[`Shared codeps artifact definitions__propagator`].min(_.fanIn, 0L).min(_.fanOut, 0L) }