package ba.sake.codeps.report

import ba.sake.tupson.JsonRW

/** Latest detailed metrics from one run. Package metrics are always available;
  * file metrics are absent only for inputs such as jdeps that cannot provide
  * file-level dependencies. */
case class MetricsReports(
    packages: MetricsReport,
    files: Option[MetricsReport] = None,
    schemaVersion: Int = 4
)

object MetricsReports:
  import ba.sake.codeps.artifacts.ArtifactCodec
  import ArtifactMappings.*
  given JsonRW[MetricsReports] = ArtifactCodec.mapped[MetricsReports, WMetricsReports]("report.schema.json")(toMetricsReports, fromMetricsReports, ba.sake.codeps.report.LegacyArtifacts.reports)
