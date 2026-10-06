package io.github.golangsupport.ide.completion

import com.intellij.util.AstLoadingFilter
import com.intellij.util.ThreeState
import io.github.golangsupport.lang.psi.GoFile

/** Package names in top-level comments ([GoCommentCompletionContributor]), as GoLand offers them on Ctrl+Space. */
class GoCommentCompletionTest : GoCompletionTestBase() {

    /** The shapes package followed by [tail] (both trimmed: [go] then only turns 4-space indents into tabs). */
    private fun src(tail: String = ""): String = """
        package main

        type Circle struct{ R float64 }

        func (c Circle) Area() float64 { return c.R }

        const maxItems = 10

        var Default = Circle{}

        func NewCircle() Circle { return Circle{} }
    """.trimIndent() + "\n\n" + tail.trimIndent()

    fun testPackageNamesExportedFirstInSourceOrderOwnNameFirst() {
        val items = lookups(src("""
            // <caret>
            func helper() {}
        """))
        assertEquals(listOf("helper", "Circle", "Area", "Default", "NewCircle", "maxItems"), items)
    }

    fun testPrefixAndOtherFilesOfThePackage() {
        myFixture.addFileToProject("other.go", "package main\n\ntype Cone struct{}\n\nfunc (Cone) Volume() float64 { return 0 }\n")
        myFixture.addFileToProject("sub/x.go", "package sub\n\ntype Cube struct{}\n")
        val items = lookups(src("""
            // C<caret>
            func helper() {}
        """))
        // start matches before the hump match, each in package order
        assertEquals(listOf("Circle", "Cone", "NewCircle"), items)
        // a method of another file, the only match: inserted
        assertNull(complete(src("// Vol<caret>\n")))
        assertTrue(myFixture.editor.document.text.endsWith("// Volume\n"))
    }

    fun testOwnNameOfTypeSpecAndVarGroupComesFirst() {
        val items = lookups(src("""
            // <caret>
            type Square struct{}
        """))
        assertEquals("Square", items.first())
        val vars = lookups(src("""
            var (
                // <caret>
                limit = 3
            )
        """))
        assertEquals("limit", vars.first())
    }

    fun testSingleMatchIsInserted() {
        checkInsert(src("// Squ<caret>\ntype Square struct{}\n"), null, src("// Square\ntype Square struct{}\n"))
    }

    fun testNothingInsideFunctionsAndDirectives() {
        myFixture.addFileToProject("other.go", "package main\n\ntype Cone struct{}\n")
        complete(src("""
            func helper() {
                // Co<caret>
            }
        """))
        assertContainsNone(myFixture.lookupElementStrings.orEmpty(), "Cone")
        complete(src("//go:Co<caret>\n"))
        assertContainsNone(myFixture.lookupElementStrings.orEmpty(), "Cone")
    }

    fun testOtherFilesAreReadFromStubs() {
        myFixture.addFileToProject("other.go", "package main\n\n// Cone is a cone.\ntype Cone struct{}\n\nvar zeta, Alpha = 1, 2\n")
        myFixture.configureByText("main.go", go(src()))
        val file = myFixture.file as GoFile
        val names = AstLoadingFilter.disallowTreeLoading<List<String?>, Throwable> { GoCommentCompletion.packageNames(file).map { it.name } }
        assertEquals(listOf("Circle", "Area", "Default", "NewCircle", "Cone", "Alpha", "maxItems", "zeta"), names)
    }

    fun testConfidenceKeepsTheAutopopupOff() {
        myFixture.configureByText("main.go", go(src("// Circle is round.\n")))
        val file = myFixture.file
        val offset = file.text.indexOf("Circle is") + 3
        assertEquals(ThreeState.YES, GoCompletionConfidence().shouldSkipAutopopup(myFixture.editor, file.findElementAt(offset)!!, file, offset))
    }
}
