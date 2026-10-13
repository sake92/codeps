package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__cutAnalysis(status: "notRequested" | "completedExact" | "completedHeuristic" | "budgetExceeded", greedyCutEstimate: Option[Long], solutions: Seq[(cuts: Seq[Artifact__cut])], examinedCandidates: Long) derives JsonRW
object Artifact__cutAnalysis { given Validator[Artifact__cutAnalysis] = Validator.derived[Artifact__cutAnalysis].min(_.examinedCandidates, 0L) }