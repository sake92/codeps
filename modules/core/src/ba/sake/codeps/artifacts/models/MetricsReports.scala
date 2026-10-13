package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class MetricsReports(schemaVersion: 4, packages: MetricsReports__packages, files: Option[MetricsReports__files]) derives JsonRW
case class MetricsReports__packages(schemaVersion: 3, scope: "packages", generatedAt: Instant, summary: Artifact__summary, cycles: Seq[Artifact__cycle], propagators: Seq[Artifact__propagator], surface: Seq[Artifact__surface], orphans: Seq[String], findings: Seq[Artifact__finding], truncation: Option[(findingsOmitted: Long)]) derives JsonRW
object MetricsReports__packages { given Validator[MetricsReports__packages] = Validator.derived[MetricsReports__packages].maxItems(_.findings, 10000) }
case class MetricsReports__files(schemaVersion: 3, scope: "files", generatedAt: Instant, summary: Artifact__summary, cycles: Seq[Artifact__cycle], propagators: Seq[Artifact__propagator], surface: Seq[Artifact__surface], orphans: Seq[String], findings: Seq[Artifact__finding], truncation: Option[(findingsOmitted: Long)]) derives JsonRW
object MetricsReports__files { given Validator[MetricsReports__files] = Validator.derived[MetricsReports__files].maxItems(_.findings, 10000) }