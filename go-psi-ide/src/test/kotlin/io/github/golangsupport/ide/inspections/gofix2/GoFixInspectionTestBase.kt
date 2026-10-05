package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker

/** Highlighting with `<SYNTAX_UPDATE descr="…">` markers, then one quick fix and the text after it. */
abstract class GoFixInspectionTestBase : GoSemanticIdeTestBase() {

    protected abstract fun tool(): LocalInspectionTool

    protected fun highlight(text: String, fileName: String = "a.go") {
        myFixture.enableInspections(tool())
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        myFixture.checkHighlighting(false, false, true)
    }

    protected fun fix(before: String, fix: String, after: String, fileName: String = "a.go") {
        highlight(before, fileName)
        val fixes = myFixture.getAllQuickFixes()
        myFixture.launchAction(fixes.firstOrNull { it.text == fix } ?: error("$fix not in ${fixes.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    /** The module graph says the file is in a main module with `go [version]` (the light project has no go.mod on disk). */
    protected fun goVersion(version: String) {
        val module = GoModule("example.com/m", null, null, null, version, isMain = true)
        val graph = GoModuleGraph(listOf(module), listOf(module), null, false, null, emptyList(), GoModuleGraph.Source.PURE)
        project.replaceService(GoModuleGraphProvider::class.java, object : GoModuleGraphProvider {
            override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph = graph
        }, testRootDisposable)
        GoProjectModelTracker.getInstance(project).bump("test: go $version")
    }

    override fun tearDown() {
        try {
            GoProjectModelTracker.getInstance(project).bump("test: restore the module graph")
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }
}
