package io.github.golangsupport.project.api

import io.github.golangsupport.lang.psi.GoBuildConstraint
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator.goodOSArchFile
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator.matchFile
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator.parse
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator.parseExpr
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator.parsePlusBuildExpr
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator.shouldBuild
import junit.framework.TestCase

/**
 * Table tests for [GoBuildConstraintEvaluator].
 *
 * Data ported from the Go 1.27 sources (BSD-style license, The Go Authors):
 * - `$GOROOT/src/go/build/constraint/expr_test.go`: exprStringTests, lexTests (as parse errors),
 *   parseExprTests, parseExprErrorTests, exprEvalTests, parsePlusBuildExprTests, constraintTests,
 *   TestSizeLimits, TestPlusSizeLimits;
 * - `$GOROOT/src/go/build/constraint/vers_test.go`: TestGoVersion;
 * - `$GOROOT/src/go/build/build_test.go`: shouldBuildTests, TestGoodOSArchFile, matchFileTests;
 * - `$GOROOT/src/go/build/syslist_test.go`: TestGoodOSArch (with thisOS=linux, thisArch=amd64).
 */
class GoBuildConstraintEvaluatorTest : TestCase() {

    private fun tag(t: String) = GoConstraintExpr.Tag(t)
    private fun not(x: GoConstraintExpr) = GoConstraintExpr.Not(x)
    private fun and(x: GoConstraintExpr, y: GoConstraintExpr) = GoConstraintExpr.And(x, y)
    private fun or(x: GoConstraintExpr, y: GoConstraintExpr) = GoConstraintExpr.Or(x, y)

    // ---- expr_test.go ------------------------------------------------------------------------

    fun testExprString() {
        val cases = listOf(
            tag("abc") to "abc",
            not(tag("abc")) to "!abc",
            not(and(tag("abc"), tag("def"))) to "!(abc && def)",
            and(tag("abc"), or(tag("def"), tag("ghi"))) to "abc && (def || ghi)",
            or(and(tag("abc"), tag("def")), tag("ghi")) to "(abc && def) || ghi",
        )
        for ((x, out) in cases) assertEquals(out, x.toString())
    }

    /** lexTests: valid tags lex as one token; the invalid ones surface as syntax errors from parseExpr. */
    fun testLex() {
        for (t in listOf("x", "x.y", "x_y", "αx", "go1.2")) assertEquals(tag(t), parseExpr(t))
        val errors = listOf(
            "αx²" to "invalid syntax at ²",
            "x~" to "invalid syntax at ~",
            "x ~" to "invalid syntax at ~",
            "x &" to "invalid syntax at &",
            "x &y" to "invalid syntax at &",
        )
        for ((input, message) in errors) {
            val e = assertThrows(input) { parseExpr(input) }
            assertEquals(input, message, e.message)
        }
        // "x y", "x!y": lexable but not a valid expression.
        assertEquals("unexpected token y", assertThrows("x y") { parseExpr("x y") }.message)
        assertEquals("unexpected token !", assertThrows("x!y") { parseExpr("x!y") }.message)
    }

    fun testParseExpr() {
        val cases = listOf(
            "x" to tag("x"),
            "x&&y" to and(tag("x"), tag("y")),
            "x||y" to or(tag("x"), tag("y")),
            "(x)" to tag("x"),
            "x||y&&z" to or(tag("x"), and(tag("y"), tag("z"))),
            "x&&y||z" to or(and(tag("x"), tag("y")), tag("z")),
            "x&&(y||z)" to and(tag("x"), or(tag("y"), tag("z"))),
            "(x||y)&&z" to and(or(tag("x"), tag("y")), tag("z")),
            "!(x&&y)" to not(and(tag("x"), tag("y"))),
        )
        for ((input, x) in cases) assertEquals(input, x.toString(), parseExpr(input).toString())
    }

    fun testParseError() {
        val cases = listOf(
            Triple("x && ", 5, "unexpected end of expression"),
            Triple("x && (", 6, "missing close paren"),
            Triple("x && ||", 5, "unexpected token ||"),
            Triple("x && !", 6, "unexpected end of expression"),
            Triple("x && !!", 6, "double negation not allowed"),
            Triple("x !", 2, "unexpected token !"),
            Triple("x && (y", 5, "missing close paren"),
        )
        for ((input, offset, message) in cases) {
            val e = assertThrows(input) { parseExpr(input) }
            assertEquals(input, message, e.message)
            assertEquals(input, offset, e.offset)
        }
    }

    fun testExprEval() {
        val cases = listOf(
            Triple("x", false, "x"),
            Triple("x && y", false, "x y"),
            Triple("x || y", false, "x y"),
            Triple("!x && yes", true, "x yes"),
            Triple("yes || y", true, "y yes"),
        )
        for ((input, ok, tags) in cases) {
            val seen = mutableSetOf<String>()
            val result = parseExpr(input).eval { seen += it; it == "yes" }
            assertEquals(input, ok, result)
            assertEquals(input, tags.split(' ').toSet(), seen)
        }
    }

    fun testParsePlusBuildExpr() {
        val cases = listOf(
            "x" to tag("x"),
            "x,y" to and(tag("x"), tag("y")),
            "x y" to or(tag("x"), tag("y")),
            "x y,z" to or(tag("x"), and(tag("y"), tag("z"))),
            "x,y z" to or(and(tag("x"), tag("y")), tag("z")),
            "x,!y !z" to or(and(tag("x"), not(tag("y"))), not(tag("z"))),
            "!! x" to or(tag("ignore"), tag("x")),
            "!!x" to tag("ignore"),
            "!x" to not(tag("x")),
            "!" to tag("ignore"),
            "" to tag("ignore"),
        )
        for ((input, x) in cases) assertEquals(input, x.toString(), parsePlusBuildExpr(input).toString())
    }

    fun testParse() {
        val ok = listOf(
            "//+build !" to tag("ignore"),
            "//+build" to tag("ignore"),
            "//+build x y" to or(tag("x"), tag("y")),
            "// +build x y \n" to or(tag("x"), tag("y")),
            "//go:build x && y" to and(tag("x"), tag("y")),
            "//go:build x && y\n" to and(tag("x"), tag("y")),
        )
        for ((input, x) in ok) assertEquals(input, x.toString(), parse(input).toString())
        val bad = listOf(
            "// +build x y \n " to "not a build constraint",
            "// +build x y \nmore" to "not a build constraint",
            " //+build x y" to "not a build constraint",
            "//go:build x && y\n " to "not a build constraint",
            "//go:build x && y\nmore" to "not a build constraint",
            " //go:build x && y" to "not a build constraint",
            "//go:build\n" to "unexpected end of expression",
        )
        for ((input, message) in bad) {
            val e = assertThrows(input) { parse(input) }
            assertTrue("$input: ${e.message}", e.message!!.contains(message))
        }
    }

    fun testSizeLimits() {
        val maxSize = 1000
        for (expr in listOf("a || ", "a && ", "(a &&", "(a ||").map { "//go:build " + it.repeat(maxSize + 2) }) {
            assertEquals("build expression too large", assertThrows(expr.take(20)) { parse(expr) }.message)
        }
        val maxOldSize = 100
        for (expr in listOf("a ", "a,").map { "// +build " + it.repeat(maxOldSize + 2) }) {
            assertEquals("expression too complex for // +build lines", assertThrows(expr.take(20)) { parse(expr) }.message)
        }
    }

    /** vers_test.go TestGoVersion. */
    fun testGoVersion() {
        val cases = listOf(
            "//go:build linux && go1.60" to 60,
            "//go:build ignore && go1.60" to 60,
            "//go:build ignore || go1.60" to -1,
            "//go:build go1.50 || (ignore && go1.60)" to 50,
            "// +build go1.60,linux" to 60,
            "// +build go1.60 linux" to -1,
            "//go:build go1.50 && !go1.60" to 50,
            "//go:build !go1.60" to -1,
            "//go:build linux && go1.50 || darwin && go1.60" to 50,
            "//go:build linux && go1.50 || !(!darwin || !go1.60)" to 50,
        )
        for ((input, out) in cases) {
            val want = when {
                out == 0 -> "go1"
                out > 0 -> "go1.$out"
                else -> null
            }
            assertEquals(input, want, GoBuildConstraintEvaluator.goVersion(parse(input)))
        }
    }

    // ---- build_test.go -----------------------------------------------------------------------

    private data class ShouldBuild(val name: String, val content: String, val tags: Set<String>, val shouldBuild: Boolean, val binaryOnly: Boolean = false)

    fun testShouldBuild() {
        val cases = listOf(
            ShouldBuild("Yes", "// +build yes\n\npackage main\n", setOf("yes"), true),
            ShouldBuild("Yes2", "//go:build yes\npackage main\n", setOf("yes"), true),
            ShouldBuild("Or", "// +build no yes\n\npackage main\n", setOf("yes", "no"), true),
            ShouldBuild("Or2", "//go:build no || yes\npackage main\n", setOf("yes", "no"), true),
            ShouldBuild("And", "// +build no,yes\n\npackage main\n", setOf("yes", "no"), false),
            ShouldBuild("And2", "//go:build no && yes\npackage main\n", setOf("yes", "no"), false),
            ShouldBuild(
                "Cgo",
                "// +build cgo\n\n// Copyright The Go Authors.\n\n// This package implements parsing of tags like\n// +build tag1\npackage build",
                setOf("cgo"), false,
            ),
            ShouldBuild(
                "Cgo2",
                "//go:build cgo\n// Copyright The Go Authors.\n\n// This package implements parsing of tags like\n// +build tag1\npackage build",
                setOf("cgo"), false,
            ),
            ShouldBuild(
                "AfterPackage",
                "// Copyright The Go Authors.\n\npackage build\n\n// shouldBuild checks tags given by lines of the form\n// +build tag\n//go:build tag\nfunc shouldBuild(content []byte)\n",
                emptySet(), true,
            ),
            ShouldBuild("TooClose", "// +build yes\npackage main\n", emptySet(), true),
            ShouldBuild("TooClose2", "//go:build yes\npackage main\n", setOf("yes"), true),
            ShouldBuild("TooCloseNo", "// +build no\npackage main\n", emptySet(), true),
            ShouldBuild("TooCloseNo2", "//go:build no\npackage main\n", setOf("no"), false),
            ShouldBuild("BinaryOnly", "//go:binary-only-package\n// +build yes\npackage main\n", emptySet(), true, binaryOnly = true),
            ShouldBuild("BinaryOnly2", "//go:binary-only-package\n//go:build no\npackage main\n", setOf("no"), false, binaryOnly = true),
            ShouldBuild("ValidGoBuild", "// +build yes\n\n//go:build no\npackage main\n", setOf("no"), false),
            ShouldBuild("MissingBuild2", "/* */\n// +build yes\n\n//go:build no\npackage main\n", setOf("no"), false),
            ShouldBuild("Comment1", "/*\n//go:build no\n*/\n\npackage main\n", emptySet(), true),
            ShouldBuild("Comment2", "/*\ntext\n*/\n\n//go:build no\npackage main\n", setOf("no"), false),
            ShouldBuild("Comment3", "/*/*/ /* hi *//* \ntext\n*/\n\n//go:build no\npackage main\n", setOf("no"), false),
            ShouldBuild("Comment4", "/**///go:build no\npackage main\n", emptySet(), true),
            ShouldBuild("Comment5", "/**/\n//go:build no\npackage main\n", setOf("no"), false),
        )
        // Context{BuildTags: []string{"yes"}}: no GOOS/GOARCH/compiler, cgo off.
        val ctx = GoBuildContext(goos = "", goarch = "", cgoEnabled = false, compiler = "", buildTags = setOf("yes"))
        for (c in cases) {
            val seen = mutableSetOf<String>()
            val result = shouldBuild(c.content) { t -> seen += t; ctx.matchTag(t) }
            assertEquals(c.name, c.shouldBuild, result)
            assertEquals(c.name, c.tags, seen)
            assertEquals(c.name, c.binaryOnly, GoBuildConstraintEvaluator.parseFileHeader(c.content).binaryOnly)
        }
    }

    fun testGoodOSArchFileTags() {
        val ctx = GoBuildContext(goos = "darwin", goarch = "", cgoEnabled = false, compiler = "", buildTags = setOf("linux"))
        val seen = mutableSetOf<String>()
        assertTrue(goodOSArchFile("hello_linux.go") { t -> seen += t; ctx.matchTag(t) })
        assertEquals(setOf("linux"), seen)
    }

    /** syslist_test.go TestGoodOSArch, pinned to thisOS=linux, thisArch=amd64. */
    fun testGoodOSArch() {
        val thisOS = "linux"
        val thisArch = "amd64"
        val otherOS = "darwin"
        val otherArch = "386"
        val ctx = GoBuildContext(thisOS, thisArch)
        val cases = listOf(
            "file.go" to true,
            "file.c" to true,
            "file_foo.go" to true,
            "file_$thisArch.go" to true,
            "file_$otherArch.go" to false,
            "file_$thisOS.go" to true,
            "file_$otherOS.go" to false,
            "file_${thisOS}_$thisArch.go" to true,
            "file_${otherOS}_$thisArch.go" to false,
            "file_${thisOS}_$otherArch.go" to false,
            "file_${otherOS}_$otherArch.go" to false,
            "file_foo_$thisArch.go" to true,
            "file_foo_$otherArch.go" to false,
            "file_$thisOS.c" to true,
            "file_$otherOS.c" to false,
        )
        for ((name, result) in cases) assertEquals(name, result, goodOSArchFile(name, ctx))
    }

    fun testMatchFile() {
        val p9 = GoBuildContext(goos = "plan9", goarch = "arm", cgoEnabled = false, compiler = "")
        val android = GoBuildContext(goos = "android", goarch = "arm", cgoEnabled = false, compiler = "")
        val cases = listOf(
            Triple(p9, "foo_arm.go", "") to true,
            Triple(p9, "foo1_arm.go", "// +build linux\n\npackage main\n") to false,
            Triple(p9, "foo_darwin.go", "") to false,
            Triple(p9, "foo.go", "") to true,
            Triple(p9, "foo1.go", "// +build linux\n\npackage main\n") to false,
            Triple(p9, "foo.badsuffix", "") to false,
            Triple(android, "foo_linux.go", "") to true,
            Triple(android, "foo_android.go", "") to true,
            Triple(android, "foo_plan9.go", "") to false,
            Triple(android, "android.go", "") to true,
            Triple(android, "plan9.go", "") to true,
            Triple(android, "plan9_test.go", "") to true,
            Triple(android, "arm.s", "") to true,
            Triple(android, "amd64.s", "") to true,
        )
        for ((input, match) in cases) {
            val (ctx, name, data) = input
            assertEquals(name, match, matchFile(name, data, ctx))
        }
    }

    // ---- go-psi specific ---------------------------------------------------------------------

    fun testSpecialTags() {
        val linux = GoBuildContext.LINUX_AMD64.copy(goVersion = GoVersion("1.21.3"), buildTags = setOf("integration"))
        assertTrue(linux.matchTag("unix"))
        assertTrue(linux.matchTag("cgo"))
        assertTrue(linux.matchTag("gc"))
        assertFalse(linux.matchTag("gccgo"))
        assertTrue(linux.matchTag("go1"))
        assertTrue(linux.matchTag("go1.21"))
        assertTrue(linux.matchTag("go1.9"))
        assertFalse(linux.matchTag("go1.22"))
        assertFalse(linux.matchTag("go1.021"))
        assertTrue(linux.matchTag("integration"))
        assertFalse(linux.matchTag("e2e"))
        assertFalse(linux.copy(cgoEnabled = false).matchTag("cgo"))

        val windows = GoBuildContext("windows", "amd64")
        assertFalse(windows.matchTag("unix"))
        assertTrue(windows.matchTag("go1.99")) // no toolchain version: every release tag holds

        val ios = GoBuildContext("ios", "arm64")
        assertTrue(ios.matchTag("darwin"))
        assertTrue(ios.matchTag("unix"))
        val android = GoBuildContext("android", "arm64")
        assertTrue(android.matchTag("linux"))
        assertTrue(GoBuildContext("illumos", "amd64").matchTag("solaris"))
        assertTrue(GoBuildContext("linux", "amd64", toolTags = setOf("goexperiment.boringcrypto")).matchTag("boringcrypto"))

        // File names: ios implies darwin, android implies linux, unknown suffixes are ignored.
        assertTrue(goodOSArchFile("x_darwin.go", ios))
        assertTrue(goodOSArchFile("x_linux_arm64.go", android))
        assertTrue(goodOSArchFile("x_unix.go", windows))
        assertFalse(goodOSArchFile("x_linux_test.go", windows))
        assertTrue(goodOSArchFile("x_windows_amd64_test.go", windows))
    }

    fun testShouldBuildWithContext() {
        val linux = GoBuildContext.LINUX_AMD64.copy(goVersion = GoVersion("1.21"))
        assertTrue(shouldBuild("//go:build linux && go1.21\n\npackage p\n", linux))
        assertFalse(shouldBuild("//go:build linux && go1.22\n\npackage p\n", linux))
        assertTrue(shouldBuild("//go:build unix && !windows\n\npackage p\n", linux))
        assertFalse(shouldBuild("//go:build ignore\n\npackage main\n", linux))
        // +build: lines are ANDed, options ORed, terms ANDed.
        assertTrue(shouldBuild("// +build linux darwin\n// +build amd64\n\npackage p\n", linux))
        assertFalse(shouldBuild("// +build linux darwin\n// +build arm64\n\npackage p\n", linux))
        assertFalse(shouldBuild("// +build linux,!cgo\n\npackage p\n", linux))
        // A malformed //go:build line excludes the file.
        assertFalse(shouldBuild("//go:build linux &&\n\npackage p\n", linux))
        // Two //go:build lines are an error.
        assertFalse(shouldBuild("//go:build linux\n//go:build amd64\n\npackage p\n", linux))
    }

    fun testMatchesCoreConstraint() {
        val linux = GoBuildContext.LINUX_AMD64
        assertTrue(GoBuildConstraintEvaluator.matches(GoBuildConstraint(null, emptyList()), linux))
        assertTrue(GoBuildConstraintEvaluator.matches(GoBuildConstraint("linux || darwin", emptyList()), linux))
        assertFalse(GoBuildConstraintEvaluator.matches(GoBuildConstraint("windows", listOf("+build linux")), linux))
        assertTrue(GoBuildConstraintEvaluator.matches(GoBuildConstraint(null, listOf("+build linux darwin", "+build amd64")), linux))
        assertFalse(GoBuildConstraintEvaluator.matches(GoBuildConstraint(null, listOf("+build linux", "+build arm")), linux))
        val header = GoBuildConstraintEvaluator.parseFileHeader("// +build linux\n//go:build linux && cgo\n\npackage p\n")
        assertEquals(GoBuildConstraint("linux && cgo", listOf("+build linux")), header.toBuildConstraint())
    }

    fun testGoVersionOrdering() {
        val ordered = listOf("1", "1.20", "1.21", "1.21rc1", "1.21rc2", "1.21.0", "1.21.1", "1.21.10", "1.22", "1.22beta1", "1.22rc1", "1.22.0", "2.0")
        for (i in ordered.indices) for (j in ordered.indices) {
            val c = GoVersion(ordered[i]).compareTo(GoVersion(ordered[j]))
            val expected = i.compareTo(j)
            assertEquals("${ordered[i]} vs ${ordered[j]}", expected.coerceIn(-1, 1), c.coerceIn(-1, 1))
        }
        assertTrue(GoVersion("1.22") < GoVersion("1.22beta1"))
        assertEquals(GoVersion("1.27.1"), GoVersion.parse("go1.27.1\ntime 2026-08-28T16:20:06Z\n"))
        assertEquals(GoVersion("1.21"), GoVersion.parse("go1.21rc2")?.languageVersion)
        assertEquals(27, GoVersion.parse("go1.27.1")?.minor)
        assertNull(GoVersion.parse("devel +abcdef"))
        assertNull(GoVersion.parse("1.21.0rc1"))
        assertEquals("go1.21.3", GoVersion("1.21.3").toString())
    }

    private fun assertThrows(what: String, block: () -> Unit): GoConstraintSyntaxException {
        try {
            block()
        } catch (e: GoConstraintSyntaxException) {
            return e
        }
        fail("$what: expected a GoConstraintSyntaxException")
        throw AssertionError()
    }
}
