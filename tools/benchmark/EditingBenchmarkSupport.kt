package io.github.golangsupport.benchmark

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * Helpers of the editing-performance benchmarks (docs/TESTING.md, "Editing performance"); compiled
 * into the test source set of every module together with [BenchmarkSupport].
 */
object EditingBenchmarkSupport {

    /**
     * Copies the non-test `.go` files of `$GOROOT/src/<dir>` into the light project under [target]
     * (one package directory). Returns the copies by file name. Nothing from GOROOT is committed;
     * build constraints are left to the package model (linux/amd64 in the semantic tests).
     */
    fun copyGorootPackage(fixture: CodeInsightTestFixture, dir: String, target: String): Map<String, PsiFile> =
        BenchmarkSupport.goFilesIn(dir).filter { !it.first.endsWith("_test.go") }
            .associate { (name, text) -> name to fixture.addFileToProject("$target/$name", text) }

    /**
     * A reversible edit: every [toggle] inserts [text] at the offset found by [anchor] (+ [delta]) or
     * removes it again, then commits the document. The document returns to its original text after
     * an even number of toggles, so iterations measure the same edit.
     */
    class ToggleEdit(private val project: Project, val file: PsiFile, anchor: String, delta: Int, private val text: String) {
        val document: Document = PsiDocumentManager.getInstance(project).getDocument(file) ?: error("no document for ${file.name}")
        val offset: Int
        var applied = false
            private set

        init {
            val at = document.text.indexOf(anchor)
            check(at >= 0) { "anchor not found in ${file.name}: $anchor" }
            offset = at + delta
        }

        /** Toggles the edit and commits (the re-parse is part of the call, keep it out of timed segments). */
        fun toggle() {
            WriteCommandAction.runWriteCommandAction(project) {
                if (applied) document.deleteString(offset, offset + text.length) else document.insertString(offset, text)
                applied = !applied
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }

        /** Leaves the document unedited. */
        fun reset() {
            if (applied) toggle()
        }
    }

    /** Offset right after the first `{\n` following [anchor] (the start of a function body's first line). */
    fun bodyStart(text: String, anchor: String): Int {
        val at = text.indexOf(anchor)
        check(at >= 0) { "anchor not found: $anchor" }
        return text.indexOf("{\n", at) + 2 - at
    }
}
