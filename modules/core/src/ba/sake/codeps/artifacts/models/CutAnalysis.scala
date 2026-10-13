package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class CutAnalysis(status: "notRequested" | "completedExact" | "completedHeuristic" | "budgetExceeded", greedyCutEstimate: Option[Int], solutions: Seq[Solution], examinedCandidates: Int) derives JsonRW
object CutAnalysis { given Validator[CutAnalysis] = Validator.derived[CutAnalysis].min(_.examinedCandidates, 0).max(_.examinedCandidates, 2147483647) }