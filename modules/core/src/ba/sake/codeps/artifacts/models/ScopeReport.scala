package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class ScopeReport(schemaVersion: 3, scope: "packages" | "files", generatedAt: Instant, summary: Summary, cycles: Seq[Cycle], propagators: Seq[Propagator], surface: Seq[Surface], orphans: Seq[String], findings: Seq[Finding], truncation: Option[ReportTruncation]) derives JsonRW
object ScopeReport { given Validator[ScopeReport] = Validator.derived[ScopeReport].maxItems(_.findings, 10000) }