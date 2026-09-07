package ba.sake.codeps.report

import ba.sake.tupson.JsonRW

/** Latest detailed metrics from one run. Package metrics are always available;
  * file metrics are absent only for inputs such as jdeps that cannot provide
  * file-level dependencies. */
case class MetricsReports(
    packages: MetricsReport,
    files: Option[MetricsReport] = None,
    schemaVersion: Int = 3
) derives JsonRW
