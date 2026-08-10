package ba.sake.codeps.json

import ba.sake.codeps.model.PackageDeps
import ba.sake.tupson.{*, given}

object JsonParser:

  /**
    * Parses the common JSON input format, a serialization of [[PackageDeps]]:
    * `{"own": [...], "edges": [{"source": "...", "target": "..."}], "stats": {...}}`.
    * Missing `own`/`edges` default to empty; unknown keys are ignored.
    */
  def parse(text: String): Either[String, PackageDeps] =
    try Right(text.parseJson[PackageDeps])
    catch
      case e: ba.sake.tupson.TupsonException => Left(s"failed to parse json: ${e.getMessage}")
