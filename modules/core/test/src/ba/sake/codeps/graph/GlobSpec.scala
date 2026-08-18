package ba.sake.codeps.graph

class GlobSpec extends munit.FunSuite:

  test("**/test/** matches a whole `test` path segment anywhere, not substrings") {
    assert(Glob.matches("**/test/**")("src/test/scala/Foo.scala"))
    assert(Glob.matches("**/test/**")("modules/core/test/src/Foo.scala"))
    assert(Glob.matches("**/test/**")("test/Foo.scala"))
    assert(!Glob.matches("**/test/**")("src/main/scala/Foo.scala"))
    assert(!Glob.matches("**/test/**")("src/testing/Foo.scala"))
    assert(!Glob.matches("**/test/**")("src/contest/Foo.scala"))
  }

  test("* does not cross /, ? matches one char, other chars are literal") {
    assert(Glob.matches("**/*.test.scala")("com/example/util/Helper.test.scala"))
    assert(Glob.matches("**/*.test.scala")("Helper.test.scala"))
    assert(!Glob.matches("**/*.test.scala")("com/example/util/Helper.scala"))
    assert(!Glob.matches("**/*.test.scala")("com/example/util/Helper.tests.scala"))
    assert(Glob.matches("**/*Spec.scala")("com/example/util/HelperSpec.scala"))
    assert(!Glob.matches("**/*Spec.scala")("com/example/util/HelperSpec2.scala"))
    assert(Glob.matches("**/*Spec.j?va")("com/example/util/HelperSpec.java"))
    assert(!Glob.matches("**/*Spec.scala")("com/example/util/helperspec.scala"))
  }

  test("literal runs are quoted whole: metacharacters and non-BMP chars match") {
    assert(Glob.matches("**/*.😀")("a/b/Foo.😀"))
    assert(Glob.matches("a$b[c]")("a$b[c]"))
    assert(!Glob.matches("a$b[c]")("axb[c]"))
  }

  test("anchoring, * not crossing /, empty pattern, bare **") {
    assert(!Glob.matches("test")("test/Foo.scala"))
    assert(Glob.matches("*.scala")("Foo.scala"))
    assert(!Glob.matches("*.scala")("a/Foo.scala"))
    assert(Glob.matches("")(""))
    assert(!Glob.matches("")("a"))
    assert(Glob.matches("**")("a/b/c"))
  }
