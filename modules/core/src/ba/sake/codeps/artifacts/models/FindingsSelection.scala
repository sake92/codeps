package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class FindingsSelection(schemaVersion: 1, project: String, scope: "packages" | "files", generatedAt: Instant, total: Int, omitted: Int, sourceFindingsOmitted: Int, findings: Seq[Finding]) derives JsonRW
object FindingsSelection { given Validator[FindingsSelection] = Validator.derived[FindingsSelection].min(_.total, 0).max(_.total, 2147483647).min(_.omitted, 0).max(_.omitted, 2147483647).min(_.sourceFindingsOmitted, 0).max(_.sourceFindingsOmitted, 2147483647).maxItems(_.findings, 10000) }