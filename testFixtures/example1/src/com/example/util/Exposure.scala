package com.example.util

import scala.math.Ordering

/** Exercises the Scala adapter's exposure rules. Deliberately self-contained:
  * only external (stdlib) references, so no new internal edges appear — existing
  * edge assertions stay valid. */
sealed trait SealedBase:
  def sealedMethod(): Int = 1

final class SealedImpl extends SealedBase

class Exposure:
  var counter: Int = 0
  val buffer: scala.collection.mutable.ArrayBuffer[Int] = scala.collection.mutable.ArrayBuffer.empty
  def fresh: scala.collection.mutable.Buffer[Int] = scala.collection.mutable.ArrayBuffer.empty
  val plain: String = "x"
  private val hidden: Int = 1
  protected def prot(): Unit = ()
  private[util] def pkgPriv(): Unit = ()

object Exposure:
  given intOrdering: Ordering[Int] = Ordering.Int
  implicit def stringConv: Conversion[String, Int] = _.length
