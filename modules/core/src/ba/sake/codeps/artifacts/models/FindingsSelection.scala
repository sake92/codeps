package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class FindingsSelection(schemaVersion: 1, project: String, scope: "packages" | "files", generatedAt: Instant, total: Long, omitted: Long, sourceFindingsOmitted: Long, findings: Seq[Artifact__finding]) derives JsonRW
object FindingsSelection { given Validator[FindingsSelection] = Validator.derived[FindingsSelection].min(_.total, 0L).min(_.omitted, 0L).min(_.sourceFindingsOmitted, 0L).maxItems(_.findings, 10000) }