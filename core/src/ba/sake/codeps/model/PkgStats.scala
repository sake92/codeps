package ba.sake.codeps.model

/** Per-package metadata gathered while parsing sources (files = source files, classes = class-like types). */
case class PkgStats(fileCount: Int, classCount: Int):
  def +(other: PkgStats): PkgStats = PkgStats(fileCount + other.fileCount, classCount + other.classCount)
