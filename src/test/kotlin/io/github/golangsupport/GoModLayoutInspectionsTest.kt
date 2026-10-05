package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.mod.GoModMigrateToWorkspaceInspection
import io.github.golangsupport.mod.GoModRequireDirectivesMergeInspection
import io.github.golangsupport.mod.GoModUnresolvedIgnorePathInspection

/** VgoRequireDirectivesMerge, VgoMigrateFromReplacesToWorkspace, VgoUnresolvedIgnorePath in a go.mod editor, with their fixes applied. */
class GoModLayoutInspectionsTest : BasePlatformTestCase() {
    private val settings get() = io.github.golangsupport.settings.GoSettings.getInstance()
    private var server = true

    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        settings.languageServerEnabled = false
    }

    override fun tearDown() {
        try { settings.languageServerEnabled = server } finally { super.tearDown() }
    }

    fun testRequiresAreMerged() {
        myFixture.enableInspections(GoModRequireDirectivesMergeInspection::class.java)
        myFixture.configureByText("go.mod", "module m\n\ngo 1.22\n\nrequire a.com/x v1.0.0\nrequire b.com/y v1.0.0 // indirect\nrequire c.com/<caret>z v1.0.0\n")
        myFixture.launchAction(myFixture.findSingleIntention("Merge 'require' directives"))
        myFixture.checkResult("module m\n\ngo 1.22\n\nrequire (\n\ta.com/x v1.0.0\n\tc.com/z v1.0.0\n)\n\nrequire (\n\tb.com/y v1.0.0 // indirect\n)\n")
    }

    fun testTidyLayoutIsQuiet() {
        myFixture.enableInspections(GoModRequireDirectivesMergeInspection::class.java)
        myFixture.configureByText("go.mod", "module m\n\nrequire (\n\ta.com/<caret>x v1.0.0\n)\n\nrequire (\n\tb.com/y v1.0.0 // indirect\n)\n")
        assertEmpty(myFixture.filterAvailableIntentions("Merge 'require' directives"))
    }

    fun testLocalReplaceOffersAWorkspace() {
        myFixture.enableInspections(GoModMigrateToWorkspaceInspection::class.java)
        myFixture.addFileToProject("lib/go.mod", "module example.com/lib\n")
        val warning = "<warning descr=\"Migration to Go workspace is possible\">./lib</warning>"
        val mod = myFixture.configureByText("go.mod", "module example.com/app\n\ngo 1.22\n\nrequire example.com/lib v0.0.0\n\nreplace example.com/lib => $warning\n")
        myFixture.checkHighlighting()
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("./lib"))
        myFixture.launchAction(myFixture.findSingleIntention("Create go.work"))
        myFixture.checkResult("module example.com/app\n\ngo 1.22\n\nrequire example.com/lib v0.0.0\n")
        val work = mod.virtualFile.parent.findChild("go.work")
        assertNotNull(work)
        assertEquals("go 1.22\n\nuse (\n\t.\n\t./lib\n)\n", String(work!!.contentsToByteArray()))
    }

    fun testNoWorkspaceOfferUnderAGoWork() {
        myFixture.enableInspections(GoModMigrateToWorkspaceInspection::class.java)
        myFixture.addFileToProject("w/lib/go.mod", "module example.com/lib\n")
        myFixture.addFileToProject("w/go.work", "go 1.22\n\nuse ./app\n")
        val mod = myFixture.addFileToProject("w/app/go.mod", "module example.com/app\n\nreplace example.com/lib => ../lib\n")
        myFixture.configureFromExistingVirtualFile(mod.virtualFile)
        assertEmpty(myFixture.doHighlighting().filter { it.description == GoModMigrateToWorkspaceInspection.MESSAGE })
    }

    fun testUnresolvedIgnorePathIsRemoved() {
        myFixture.enableInspections(GoModUnresolvedIgnorePathInspection::class.java)
        myFixture.addFileToProject("web/node_modules/x.txt", "")
        myFixture.addFileToProject("assets/static/a.css", "")
        val warning = "<warning descr=\"Unresolved path './gone' in 'ignore' directive\">./gone</warning>"
        myFixture.configureByText("go.mod", "module m\n\ngo 1.25\n\nignore (\n\t./web/node_modules\n\tstatic\n\t$warning\n)\n")
        myFixture.checkHighlighting()
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("./gone"))
        myFixture.launchAction(myFixture.findSingleIntention("Remove the path"))
        myFixture.checkResult("module m\n\ngo 1.25\n\nignore (\n\t./web/node_modules\n\tstatic\n)\n")
    }
}
