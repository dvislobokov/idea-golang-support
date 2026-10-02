package io.github.golangsupport.lang.lexer

import io.github.golangsupport.lang.psi.GoBuildConstraint
import junit.framework.TestCase

class GoFileHeaderScannerTest : TestCase() {

    private fun constraint(text: String): GoBuildConstraint = GoFileHeaderScanner.scan(text).buildConstraint

    fun testGoBuildAndPlusBuild() {
        val c = constraint("// Copyright\n\n//go:build linux && amd64\n// +build linux,amd64\n\n// Package doc.\npackage p\n")
        assertEquals(GoBuildConstraint("linux && amd64", listOf("+build linux,amd64")), c)
    }

    fun testGoBuildCountsAnywhereInTheLeadingCommentRun() {
        // go/build TooClose2 / Comment5: a //go:build line counts even directly above `package`.
        assertEquals("linux", constraint("//go:build linux\npackage p\n").goBuild)
        assertEquals("no", constraint("/**/\n//go:build no\npackage main\n").goBuild)
        // go/build TooCloseNo: a // +build line directly above `package` does not count.
        assertTrue(constraint("// +build no\npackage main\n").plusBuild.isEmpty())
        // Inside a block comment it is not a directive.
        assertNull(constraint("/*\n//go:build linux\n*/\n\npackage p\n").goBuild)
    }

    fun testConstraintAfterPackageIsIgnored() {
        assertTrue(constraint("package p\n\n//go:build linux\n").isEmpty)
    }

    fun testGoBuildRequiresExactPrefix() {
        assertTrue(constraint("//go:buildx linux\n\npackage p\n").isEmpty)
        assertTrue(constraint("// go:build linux\n\npackage p\n").isEmpty)
        assertEquals("ignore", constraint("/* license */\n//go:build ignore\n\npackage p").goBuild)
    }

    fun testImportsAndPackage() {
        val header = GoFileHeaderScanner.scan(
            """
            package main // comment

            import "fmt"
            import (
            	str "strings" ; . "math"
            	_ `embed`
            	// a comment
            )
            import "C"

            func main() {}
            import "late"
            """.trimIndent(),
        )
        assertEquals("main", header.packageName)
        assertEquals(
            listOf(
                GoFileHeaderScanner.Import("fmt", null),
                GoFileHeaderScanner.Import("strings", "str"),
                GoFileHeaderScanner.Import("math", "."),
                GoFileHeaderScanner.Import("embed", "_"),
                GoFileHeaderScanner.Import("C", null),
            ),
            header.imports,
        )
    }

    fun testBrokenHeader() {
        assertNull(GoFileHeaderScanner.scan("func f() {}").packageName)
        assertEquals(emptyList<GoFileHeaderScanner.Import>(), GoFileHeaderScanner.scan("package p\nimport (\n\t\"a\"\n\tfoo(").imports.drop(1))
    }
}
