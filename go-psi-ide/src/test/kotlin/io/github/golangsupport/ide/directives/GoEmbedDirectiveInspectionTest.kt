package io.github.golangsupport.ide.directives

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** `GoEmbedDirectiveInspection`: every rule reported and not reported, and both forms of the import fix. */
class GoEmbedDirectiveInspectionTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String) {
        myFixture.addFileToProject("hello.txt", "hi")
        myFixture.addFileToProject("static/a.css", "a")
        myFixture.addFileToProject("empty/_x", "x")
        myFixture.enableInspections(GoEmbedDirectiveInspection())
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    private fun doFix(before: String, after: String) {
        myFixture.addFileToProject("hello.txt", "hi")
        myFixture.enableInspections(GoEmbedDirectiveInspection())
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == "Add import \"embed\"" } ?: error("fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    fun testValidDirectivesAreQuiet() = doHighlight(
        """
        package p

        import "embed"

        //go:embed hello.txt static
        // a comment between
        //go:embed static/*.css

        var f embed.FS
        """,
    )

    fun testNoMatchingFiles() = doHighlight(
        """
        package p

        import _ "embed"

        //go:embed hello.txt <error descr="pattern nope/*.txt: no matching files found">nope/*.txt</error>
        var s string
        """,
    )

    fun testInvalidPatterns() = doHighlight(
        """
        package p

        import _ "embed"

        //go:embed <error descr="pattern ../x: invalid pattern syntax">../x</error> <error descr="pattern /abs: invalid pattern syntax">/abs</error> <error descr="pattern ./a: invalid pattern syntax">./a</error> <error descr="pattern a\b: invalid pattern syntax">a\b</error> <error descr="pattern a[: invalid pattern syntax">a[</error>
        var s string
        """,
    )

    fun testDirectoryWithoutEmbeddableFiles() = doHighlight(
        """
        package p

        import _ "embed"

        //go:embed <error descr="pattern empty: cannot embed directory empty: contains no embeddable files">empty</error> all:empty
        var s string
        """,
    )

    fun testMisplaced() = doHighlight(
        """
        package p

        import _ "embed"

        <error descr="misplaced //go:embed directive">//go:embed hello.txt</error>
        func f() {}

        func g() {
        	<error descr="misplaced //go:embed directive">//go:embed hello.txt</error>
        	var s string
        	_ = s
        }
        """,
    )

    fun testMissingEmbedImportReported() = doHighlight(
        """
        package p

        <error descr="go:embed only allowed in Go files that import \"embed\"">//go:embed hello.txt</error>
        var s string
        """,
    )

    fun testFixBlankImportForString() = doFix(
        """
        package p

        //go:embed hello.txt<caret>
        var s string
        """,
        """
        package p

        import _ "embed"

        //go:embed hello.txt
        var s string
        """,
    )

    fun testFixPlainImportForEmbedFS() = doFix(
        """
        package p

        //go:embed hello.txt<caret>
        var f embed.FS
        """,
        """
        package p

        import "embed"

        //go:embed hello.txt
        var f embed.FS
        """,
    )
}
