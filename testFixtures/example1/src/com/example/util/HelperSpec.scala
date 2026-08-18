// synthetic fixture: a test file in the same package as main code (matched by the *Spec.scala naming convention)
package com.example.util

class HelperSpec:
  def verify(): Boolean =
    new Helper().help() == "help"
