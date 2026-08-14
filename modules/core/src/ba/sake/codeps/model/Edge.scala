package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/** A directed dependency edge between two node ids. */
case class Edge(source: String, target: String) derives JsonRW
