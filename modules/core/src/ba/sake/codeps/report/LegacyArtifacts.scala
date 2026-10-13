package ba.sake.codeps.report

import org.typelevel.jawn.ast.{JNum, JObject, JValue}

/** Normalize supported historical report fields before decoding the current
  * generated model.
  */
private[codeps] object LegacyArtifacts {
  private val surfaceCounters = Seq(
    "publicSurface",
    "protectedSurface",
    "packageSurface",
    "privateSurface",
    "publicMutableSurface",
    "protectedMutableSurface",
    "packageMutableSurface",
    "privateMutableSurface",
    "totalDeclaredSurface"
  )

  def surface(value: JValue): JValue = value match {
    case JObject(fields) =>
      val result = scala.collection.mutable.Map.from(fields)
      if !result.contains("dependentsPerPublicPort") then {
        result("dependentsPerPublicPort") =
          result.getOrElse("utilization", org.typelevel.jawn.ast.JNull)
      }
      result.remove("utilization")
      surfaceCounters.foreach(name => result.getOrElseUpdate(name, JNum(0)))
      JObject(result)
    case other => other
  }

  def report(value: JValue): JValue = value match {
    case JObject(fields) =>
      val result = scala.collection.mutable.Map.from(fields)
      result.get("schemaVersion").foreach { version =>
        if version != JNum(2) && version != JNum(3) then
          throw ba.sake.tupson.TupsonException(
            s"incompatible schema version: ${version.render()} (expected 2 or 3)"
          )
      }
      if result.get("schemaVersion").contains(JNum(2)) then
        result("schemaVersion") = JNum(3)
      result.get("surface").foreach {
        case org.typelevel.jawn.ast.JArray(rows) =>
          result("surface") = org.typelevel.jawn.ast.JArray(rows.map(surface))
        case _ => ()
      }
      JObject(result)
    case other => other
  }

  def reports(value: JValue): JValue = value match {
    case JObject(fields) =>
      val result = scala.collection.mutable.Map.from(fields)
      Seq("packages", "files").foreach(name =>
        result.get(name).foreach(v => result(name) = report(v))
      )
      JObject(result)
    case other => other
  }
}
