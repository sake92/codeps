package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

case class PackageEdge(source: String, target: String) derives JsonRW
