package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Shared codeps artifact definitions__cutAnalysis`(status: "notRequested" | "completedExact" | "completedHeuristic" | "budgetExceeded", greedyCutEstimate: Option[Long], solutions: Seq[(cuts: Seq[`Shared codeps artifact definitions__cut`])], examinedCandidates: Long) derives JsonRW
object `Shared codeps artifact definitions__cutAnalysis` { given Validator[`Shared codeps artifact definitions__cutAnalysis`] = Validator.derived[`Shared codeps artifact definitions__cutAnalysis`].min(_.examinedCandidates, 0L) }