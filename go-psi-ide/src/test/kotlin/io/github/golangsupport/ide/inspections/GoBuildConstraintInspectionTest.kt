package io.github.golangsupport.ide.inspections

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** `GoBuildConstraintInspection`: every rule reported and not reported, and the fixes' results. */
class GoBuildConstraintInspectionTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String) {
        myFixture.enableInspections(GoBuildConstraintInspection())
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    private fun doFix(before: String, fix: String, after: String) {
        myFixture.enableInspections(GoBuildConstraintInspection())
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    fun testSyntaxError() = doHighlight(
        """
        <error descr="invalid //go:build expression: unexpected end of expression">//go:build linux &&</error>

        package p
        """,
    )

    fun testValidExpressionAndCustomTagsAreQuiet() = doHighlight(
        """
        // Copyright header.

        //go:build (linux || darwin) && !cgo && integration

        package p
        """,
    )

    fun testMisplacedAfterPackage() = doHighlight(
        """
        package p

        <error descr="misplaced //go:build comment">//go:build linux</error>
        func f() {}
        """,
    )

    fun testMisplacedWithoutBlankLine() = doHighlight(
        """
        <error descr="misplaced //go:build comment">//go:build linux</error>
        package p
        """,
    )

    fun testMultiple() = doHighlight(
        """
        //go:build linux
        <error descr="multiple //go:build comments">//go:build darwin</error>

        package p
        """,
    )

    fun testPlusBuildDeprecated() = doHighlight(
        """
        <weak_warning descr="// +build is deprecated; use //go:build">// +build linux,amd64</weak_warning>

        package p
        """,
    )

    fun testPlusBuildWithGoBuildIsQuiet() = doHighlight(
        """
        //go:build linux && amd64
        // +build linux,amd64

        package p
        """,
    )

    fun testUnknownOs() = doHighlight(
        """
        //go:build <weak_warning descr="unknown GOOS/GOARCH 'linx'">linx</weak_warning> || darwin

        package p
        """,
    )

    fun testAddGoBuildFix() = doFix(
        """
        // +build linux,!cgo darwin

        package p
        """,
        "Add //go:build line",
        """
        //go:build (linux && !cgo) || darwin
        // +build linux,!cgo darwin

        package p
        """,
    )

    fun testReplaceTagFix() = doFix(
        """
        //go:build <caret>linx && amd64

        package p
        """,
        "Replace with 'linux'",
        """
        //go:build linux && amd64

        package p
        """,
    )
}
