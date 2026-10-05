package io.github.golangsupport.ide.inspections.gofix

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker

/**
 * Base of the Go fix inspection tests. Texts are written with 4-space indents that become tabs (gofmt), so `trimIndent` works in raw strings;
 * markers are the fixture's (`<SYNTAX_UPDATE descr="…">`). [doTest] checks the highlighting, applies the named fix and compares the result.
 */
abstract class GoFixTestBase : GoSemanticIdeTestBase() {

    protected abstract fun inspection(): LocalInspectionTool

    protected fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    /** Highlighting of [before] (with markers), then every fix named [fix] applied one by one, then [after]. */
    protected fun doTest(before: String, fix: String, after: String) {
        highlight(before)
        var applied = 0
        while (applied < 20) {
            val next = myFixture.getAllQuickFixes().firstOrNull { it.text == fix } ?: break
            myFixture.launchAction(next)
            applied++
        }
        assertTrue("no fix named '$fix'", applied > 0)
        assertEquals(go(after), myFixture.editor.document.text)
    }

    /** Only the highlighting of [text] (markers for every expected problem; none means the inspection stays quiet). */
    protected fun highlight(text: String) {
        myFixture.enableInspections(inspection())
        myFixture.configureByText("a.go", go(text))
        myFixture.checkHighlighting(false, false, true)
    }

    /** The module of every file says `go [version]` (the light project has no go.mod on disk). */
    protected fun useGoVersion(version: String) {
        val module = GoModule("example.com/m", null, null, null, version, isMain = true)
        val graph = GoModuleGraph(listOf(module), listOf(module), null, false, null, emptyList(), GoModuleGraph.Source.PURE)
        project.replaceService(GoModuleGraphProvider::class.java, object : GoModuleGraphProvider {
            override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph = graph
        }, testRootDisposable)
        val tracker = GoProjectModelTracker.getInstance(project)
        tracker.bump("test: go $version")
        // The cached version of the shared light directory must not outlive the replaced graph.
        Disposer.register(testRootDisposable) { tracker.bump("test: graph restored") }
    }

    protected fun warn(message: String, text: String) = "<SYNTAX_UPDATE descr=\"$message\">$text</SYNTAX_UPDATE>"
}
