// synthetic fixture: a test file in a test-only package (its package must vanish under --skip-tests)
package com.example.specs

import com.example.util.Helper

class OnlyTestsHereSpec:
  def verify(): Boolean =
    new Helper().help() == "help"
