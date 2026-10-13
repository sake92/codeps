package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class ReportTruncation(findingsOmitted: Int) derives JsonRW
object ReportTruncation { given Validator[ReportTruncation] = Validator.derived[ReportTruncation].min(_.findingsOmitted, 0).max(_.findingsOmitted, 2147483647) }