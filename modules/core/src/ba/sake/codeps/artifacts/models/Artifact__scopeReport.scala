package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__scopeReport(schemaVersion: 3, scope: "packages" | "files", generatedAt: Instant, summary: Artifact__summary, cycles: Seq[Artifact__cycle], propagators: Seq[Artifact__propagator], surface: Seq[Artifact__surface], orphans: Seq[String], findings: Seq[Artifact__finding], truncation: Option[(findingsOmitted: Long)]) derives JsonRW
object Artifact__scopeReport { given Validator[Artifact__scopeReport] = Validator.derived[Artifact__scopeReport].maxItems(_.findings, 10000) }