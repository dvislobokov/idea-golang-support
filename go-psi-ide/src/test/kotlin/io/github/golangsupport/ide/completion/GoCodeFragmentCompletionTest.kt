package io.github.golangsupport.ide.completion

import io.github.golangsupport.lang.psi.GoFile

/** Completion in the Go fields of the refactoring dialogs ([GoCodeFragments]): names resolve in the context file's package and imports. */
class GoCodeFragmentCompletionTest : GoCompletionTestBase() {

    private val contextText = """
        package frag

        import "context"

        type Ih9Item struct{ ID string }

        var ih9Default = Ih9Item{}

        func ih9Use(ctx context.Context) {}
    """

    private lateinit var context: GoFile

    override fun setUp() {
        super.setUp()
        context = myFixture.addFileToProject("frag/ctx.go", go(contextText)) as GoFile
    }

    /** Completes [text] (with `<caret>`) as a fragment of [kind]: the lookup strings, or the fragment text when one item was inserted. */
    private fun complete(kind: GoCodeFragments.Kind, text: String): List<String> {
        val fragment = GoCodeFragments.create(project, context, kind, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(fragment.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        return myFixture.completeBasic()?.let { myFixture.lookupElementStrings.orEmpty() } ?: listOf(myFixture.editor.document.text)
    }

    fun testImportedPackage() {
        val items = complete(GoCodeFragments.Kind.TYPE, "cont<caret>")
        assertTrue("$items", items.any { it == "context" || it == "context." })
    }

    fun testPackageMember() {
        val items = complete(GoCodeFragments.Kind.TYPE, "context.Con<caret>")
        assertTrue("$items", items.any { it == "Context" || it == "context.Context" })
    }

    fun testPackageLocalType() {
        val items = complete(GoCodeFragments.Kind.TYPE, "[]Ih9<caret>")
        assertTrue("$items", items.any { it == "Ih9Item" || it == "[]Ih9Item" })
    }

    fun testVariadicAndMapTypes() {
        assertTrue(complete(GoCodeFragments.Kind.TYPE, "...Ih9<caret>").any { it.endsWith("Ih9Item") })
        assertTrue(complete(GoCodeFragments.Kind.TYPE, "map[string]*Ih9<caret>").any { it.endsWith("Ih9Item") })
    }

    fun testResults() {
        assertTrue(complete(GoCodeFragments.Kind.RESULTS, "(n int, err erro<caret>)").any { it.endsWith("error") })
        assertTrue(complete(GoCodeFragments.Kind.RESULTS, "Ih9<caret>").any { it.endsWith("Ih9Item") })
    }

    fun testExpressionSeesPackageValues() {
        assertTrue(complete(GoCodeFragments.Kind.EXPRESSION, "ih9De<caret>").any { it.endsWith("ih9Default") })
        assertTrue(complete(GoCodeFragments.Kind.EXPRESSION, "context.Backgr<caret>").any { it.contains("Background") })
    }

    fun testUnimportedPackageIsNotImportedIntoTheFragment() {
        val items = complete(GoCodeFragments.Kind.TYPE, "strin<caret>")
        if (myFixture.lookup != null) {
            assertContainsAll(items, "strings")
            select("strings")
        }
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("strings"))
        assertFalse(myFixture.editor.document.text.contains("import"))
        assertFalse(context.text.contains("\"strings\""))
    }
}
