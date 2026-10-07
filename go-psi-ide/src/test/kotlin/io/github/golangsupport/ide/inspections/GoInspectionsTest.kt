package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.api.GoSemanticService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Highlighting of each diagnostics inspection over `testData/inspections/<name>.go` (messages are
 * the checker's text; multi-line go/types messages are joined with `; `), the `cannot infer`
 * option, `//noinspection` suppression, and the one-check-per-modification cache.
 */
class GoInspectionsTest : GoSemanticIdeTestBase() {
    override val testDataSubdir: String = "inspections"

    private fun doTest(name: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        // Each fixture is its own package directory so package-level names do not clash.
        val file = myFixture.addFileToProject("${name.lowercase()}/$name.go", readTestData("$name.go"))
        myFixture.testHighlighting(true, false, true, file.virtualFile)
    }

    fun testUnresolvedReference() = doTest("unresolved", GoUnresolvedReferenceInspection())

    fun testUnusedImport() = doTest("unusedImport", GoUnusedImportInspection())

    fun testUnusedVariable() = doTest("unusedVariable", GoUnusedVariableInspection())

    fun testUnusedLabel() = doTest("unusedLabel", GoUnusedLabelInspection())

    fun testTypeMismatch() = doTest("typeMismatch", GoTypeMismatchInspection())

    fun testCallArity() = doTest("callArity", GoCallArityInspection())

    fun testDuplicateDeclaration() = doTest("duplicate", GoDuplicateDeclarationInspection())

    /** `cannot infer` is hidden by default (generics.go has `Map(nil, nil)` without markup). */
    fun testGenerics() = doTest("generics", GoGenericsInspection())

    fun testGenericsCannotInferOption() = doTest("genericsCannotInfer", GoGenericsInspection().apply { reportCannotInfer = true })
    fun testGenericsCannotInferOptionOff() = doTest("genericsCannotInferOff", GoGenericsInspection().apply { reportCannotInfer = false })

    fun testOtherCheckerErrors() = doTest("checker", GoCheckerInspection())

    fun testMissingReturn() = doTest("missingReturn", GoMissingReturnInspection())

    fun testSuppression() = doTest(
        "suppressed",
        GoUnusedVariableInspection(), GoTypeMismatchInspection(), GoUnusedImportInspection(), GoUnusedLabelInspection(),
    )

    /** All inspections share one `check(file)` per modification of the file. */
    fun testCheckRunsOncePerModification() {
        val counting = CountingService(GoSemanticService.getInstance(project))
        project.replaceService(GoSemanticService::class.java, counting, testRootDisposable)
        myFixture.enableInspections(*GoInspectionClasses.ALL.map { it.getDeclaredConstructor().newInstance() }.toTypedArray())
        myFixture.configureByText("a.go", "package a\n\nimport \"os\"\n\nfunc f() int {\n\tx := 1\n\t<caret>\n\treturn \"s\"\n}\n")
        val first = myFixture.doHighlighting().filter { it.description != null }.map { it.description }
        assertTrue(first.toString(), first.containsAll(listOf("\"os\" imported and not used", "declared and not used: x")))
        // a compile error of Go: red wave, not the grey unused-symbol text (invisible in Darcula, seen live)
        val unused = myFixture.doHighlighting().first { it.description == "declared and not used: x" }
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.ERROR, unused.severity)
        assertNotSame(com.intellij.codeInsight.daemon.impl.HighlightInfoType.UNUSED_SYMBOL, unused.type)
        assertEquals(1, counting.checks.get())
        myFixture.doHighlighting()
        assertEquals("no modification, no new check", 1, counting.checks.get())
        myFixture.type("_ = x")
        val second = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(second.toString(), second.contains("declared and not used: x"))
        assertEquals(2, counting.checks.get())
    }

    fun testDiagnosticsAreDeduplicated() {
        val file = myFixture.configureByText("a.go", "package a\n\nfunc none() {}\n\nfunc f() {\n\t_ = none()\n}\n") as GoFile
        val all = GoDiagnosticsCache.diagnostics(file)
        assertEquals(all.toString(), all.distinct().size, all.size)
    }

    private class CountingService(private val delegate: GoSemanticService) : GoSemanticService by delegate {
        val checks = AtomicInteger()
        override fun check(file: GoFile): List<GoDiagnostic> {
            checks.incrementAndGet()
            return delegate.check(file)
        }
    }

    /** Suppression fixes insert `//noinspection` on its own line above the container. */
    fun testSuppressForStatement() = doSuppress("suppressStatement", GoSuppressByCommentFix.Kind.STATEMENT)

    fun testSuppressForDeclaration() = doSuppress("suppressDeclaration", GoSuppressByCommentFix.Kind.DECLARATION)

    fun testSuppressForFile() = doSuppress("suppressFile", GoSuppressByCommentFix.Kind.FILE)

    private fun doSuppress(name: String, kind: GoSuppressByCommentFix.Kind) {
        myFixture.configureByFile("fixes/$name.go")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val fix = GoSuppressByCommentFix("GoUnusedVariable", kind)
        assertTrue(fix.isAvailable(project, element))
        WriteCommandAction.runWriteCommandAction(project) { fix.invoke(project, element) }
        myFixture.checkResultByFile("fixes/${name}_after.go")
        myFixture.enableInspections(GoUnusedVariableInspection())
        assertEmpty(myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("declared and not used") })
    }

    fun testSuppressionAppendsToExistingComment() {
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t//noinspection GoTypeMismatch\n\t<caret>x := 1\n}\n")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val fix = GoSuppressByCommentFix("GoUnusedVariable", GoSuppressByCommentFix.Kind.STATEMENT)
        WriteCommandAction.runWriteCommandAction(project) { fix.invoke(project, element) }
        myFixture.checkResult("package a\n\nfunc f() {\n\t//noinspection GoTypeMismatch,GoUnusedVariable\n\tx := 1\n}\n")
    }
}
