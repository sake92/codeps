package ba.sake.codeps.report

import ba.sake.codeps.artifacts.models.{
  Edge as ExportEdge,
  DeclarationSurface as ExportDeclarationSurface,
  *
}

import ba.sake.tupson.{*, given}

object HealthCalculator:
  def fromReport(report: ScopeReport): HealthSnapshot =
    val severityCounts =
      report.findings.groupMapReduce(_.severity)(_ => 1)(_ + _)
    val publicSurface = report.surface.map(_.publicSurface).sum
    val publicMutableSurface = report.surface.map(_.publicMutableSurface).sum
    val totalDeclaredSurface = report.surface.map(_.totalDeclaredSurface).sum
    val cycles = HealthCycles(
      count = report.cycles.size,
      nodes = report.summary.nodesInCycles,
      largestScc = report.cycles.map(_.size).maxOption.getOrElse(0),
      internalEdges = report.cycles.map(_.internalEdges).sum
    )
    val surface = HealthSurface(
      publicSurface = publicSurface,
      publicMutableSurface = publicMutableSurface,
      totalDeclaredSurface = totalDeclaredSurface,
      encapsulationRatio = if totalDeclaredSurface == 0 then None
      else Some(publicSurface / totalDeclaredSurface)
    )
    val health = score(report, cycles, surface)
    HealthSnapshot(
      status = health.status,
      health = health,
      structure = HealthStructure(
        nodes = report.summary.nodes,
        edges = report.summary.edges
      ),
      cycles = cycles,
      surface = surface,
      findings = HealthFindings(
        critical = severityCounts.getOrElse("critical", 0),
        high = severityCounts.getOrElse("high", 0),
        medium = severityCounts.getOrElse("medium", 0),
        low = severityCounts.getOrElse("low", 0)
      )
    )

  private def score(
      report: ScopeReport,
      cycles: HealthCycles,
      surface: HealthSurface
  ): HealthScore =
    val nodeCount = math.max(report.summary.nodes, 1).toDouble
    val cycleCoverage = cycles.nodes.toDouble / nodeCount
    val cyclePenalty = if cycles.count == 0 then 0.0
    else math.min(4.0, 1.5 + 2.5 * cycleCoverage)
    val mutablePenalty = if surface.publicSurface == 0 then 0.0
    else
      math.min(2.5, 2.5 * surface.publicMutableSurface / surface.publicSurface)
    val exposedSurfacePenalty =
      surface.encapsulationRatio.fold(0.0)(ratio => math.min(2.0, 2.0 * ratio))
    val structuralUsePenalty = math.min(
      1.0,
      report.findings.count(_.kind == "structuralUse").toDouble / nodeCount
    )
    val propagatorPenalty =
      math.min(0.5, report.propagators.size.toDouble / nodeCount * 0.5)
    val factors = HealthFactors(
      cycles = healthFactor(cyclePenalty, 4.0),
      mutableSurface = healthFactor(mutablePenalty, 2.5),
      exposedSurface = healthFactor(exposedSurfacePenalty, 2.0),
      structuralUse = healthFactor(structuralUsePenalty, 1.0),
      propagators = healthFactor(propagatorPenalty, 0.5)
    )
    val rawScore = math.max(
      1.0,
      math.min(
        10.0,
        10.0 - cyclePenalty - mutablePenalty - exposedSurfacePenalty - structuralUsePenalty - propagatorPenalty
      )
    )
    val numericScore = math.round(rawScore * 100.0) / 100.0
    val status
        : "critical" | "unhealthy" | "needs-attention" | "healthy" | "excellent" =
      numericScore match
        case score if score <= 2.0 => "critical"
        case score if score <= 4.0 => "unhealthy"
        case score if score <= 6.0 => "needs-attention"
        case score if score <= 8.0 => "healthy"
        case _                     => "excellent"
    HealthScore(score = numericScore, status = status, factors = factors)

private def healthFactor(points: Double, maximum: Double): Double =
  10.0 - points / maximum * 10.0

enum HealthRecordingDecision:
  case Initial
  case Significant(metrics: Seq[String])
  case Checkpoint
  case NotSignificant

object HealthHistory:
  def parseNdjson(text: String): Either[String, Seq[HealthHistoryEntry]] =
    text.linesIterator.zipWithIndex
      .foldLeft[Either[String, Vector[HealthHistoryEntry]]](
        Right(Vector.empty)
      ) { case (acc, (line, index)) =>
        acc.flatMap { entries =>
          if line.trim.isEmpty then Right(entries)
          else
            try
              val entry = line.parseJson[HealthHistoryEntry]
              Right(entries :+ entry)
            catch
              case e: Exception =>
                Left(s"invalid history line ${index + 1}: ${e.getMessage}")
        }
      }

  /** A combined entry is meaningful when either available scope changed. */
  def decision(
      previous: Option[HealthHistoryEntry],
      current: HealthHistoryEntry,
      relativeChange: Double,
      checkpointDue: Boolean
  ): HealthRecordingDecision =
    previous match
      case None       => HealthRecordingDecision.Initial
      case Some(last) =>
        val changed = significantMetrics(
          "packages",
          last.packages,
          current.packages,
          relativeChange
        ) ++ ((last.files, current.files) match
          case (Some(before), Some(after)) =>
            significantMetrics("files", before, after, relativeChange)
          case (None, Some(_)) | (Some(_), None) => Seq("files.availability")
          case (None, None)                      => Nil)
        if changed.nonEmpty then HealthRecordingDecision.Significant(changed)
        else if checkpointDue then HealthRecordingDecision.Checkpoint
        else HealthRecordingDecision.NotSignificant

  private def numericMetrics(
      value: HealthSnapshot
  ): Seq[(String, Option[Double])] = Seq(
    "health.score" -> Some(value.health.score),
    "structure.nodes" -> Some(value.structure.nodes.toDouble),
    "structure.edges" -> Some(value.structure.edges.toDouble),
    "cycles.count" -> Some(value.cycles.count.toDouble),
    "cycles.nodes" -> Some(value.cycles.nodes.toDouble),
    "cycles.largestScc" -> Some(value.cycles.largestScc.toDouble),
    "cycles.internalEdges" -> Some(value.cycles.internalEdges.toDouble),
    "surface.publicSurface" -> Some(value.surface.publicSurface),
    "surface.publicMutableSurface" -> Some(value.surface.publicMutableSurface),
    "surface.totalDeclaredSurface" -> Some(value.surface.totalDeclaredSurface),
    "surface.encapsulationRatio" -> value.surface.encapsulationRatio,
    "findings.critical" -> Some(value.findings.critical.toDouble),
    "findings.high" -> Some(value.findings.high.toDouble),
    "findings.medium" -> Some(value.findings.medium.toDouble),
    "findings.low" -> Some(value.findings.low.toDouble)
  )

  private def significantMetrics(
      scope: String,
      before: HealthSnapshot,
      after: HealthSnapshot,
      threshold: Double
  ) =
    numericMetrics(before).zip(numericMetrics(after)).collect {
      case ((name, earlier), (_, later))
          if significant(earlier, later, threshold) =>
        s"$scope.$name"
    }

  private def significant(
      before: Option[Double],
      after: Option[Double],
      threshold: Double
  ): Boolean =
    (before, after) match
      case (None, None)                                => false
      case (Some(_), None) | (None, Some(_))           => true
      case (Some(a), Some(b)) if a == b                => false
      case (Some(0.0), Some(_)) | (Some(_), Some(0.0)) => true
      case (Some(a), Some(b)) => math.abs(b - a) / math.abs(a) > threshold
