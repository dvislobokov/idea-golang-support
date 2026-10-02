package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.inspections.printf.GoPrintfChecker
import io.github.golangsupport.lang.psi.GoFile

/**
 * vet `printf` over the PSI ([GoPrintfInspection]): highlighting of every check over `testData/inspections/printf/printf.go`
 * (vet's messages, ranges on the directive), the quick fixes, the `%w` suggestion in `Errorf`, the Go 1.20 rule for several `%w`,
 * and no false positives over GOROOT files that are full of printf calls.
 */
class GoPrintfInspectionTest : GoSemanticIdeTestBase() {
    override val testDataSubdir: String = "inspections/printf"

    fun testHighlighting() {
        myFixture.enableInspections(GoPrintfInspection())
        val file = myFixture.addFileToProject("printf/printf.go", readTestData("printf.go"))
        myFixture.testHighlighting(true, false, true, file.virtualFile)
    }

    private fun doFix(before: String, fix: String, after: String) {
        myFixture.enableInspections(GoPrintfInspection())
        myFixture.configureByText("main.go", "package main\n\nimport \"fmt\"\n\nfunc f(s string, i int, err error, ok bool) {\n\t$before\n}\n")
        myFixture.launchAction(myFixture.findSingleIntention(fix))
        myFixture.checkResult("package main\n\nimport \"fmt\"\n\nfunc f(s string, i int, err error, ok bool) {\n\t$after\n}\n")
    }

    fun testReplaceVerbForString() = doFix("fmt.Printf(\"%-5<caret>d|\\n\", s)", "Replace %d with %s", "fmt.Printf(\"%-5s|\\n\", s)")

    fun testReplaceVerbForInt() = doFix("fmt.Printf(\"n=%<caret>s\\n\", i)", "Replace %s with %d", "fmt.Printf(\"n=%d\\n\", i)")

    fun testReplaceVerbForBool() = doFix("fmt.Printf(\"%<caret>d\\n\", ok)", "Replace %d with %t", "fmt.Printf(\"%t\\n\", ok)")

    fun testRemoveExtraArgument() = doFix("fmt.Printf(\"%d\\n\", i, <caret>s)", "Remove extra argument", "fmt.Printf(\"%d\\n\", i)")

    fun testRemoveExtraArguments() = doFix("fmt.Printf(\"x\\n\", <caret>i, s)", "Remove 2 extra arguments", "fmt.Printf(\"x\\n\")")

    fun testAddPlaceholder() = doFix("fmt.Printf(\"%d\\n\", i, <caret>s)", "Add missing argument placeholder", "fmt.Printf(\"%d %v\\n\", i, s)")

    fun testAddPlaceholdersWithoutNewline() = doFix("fmt.Printf(\"x: \", <caret>i, s)", "Add 2 missing argument placeholders", "fmt.Printf(\"x: %v %v\", i, s)")

    fun testErrorfSuggestsWrapping() = doFix("_ = fmt.Errorf(\"read: %<caret>v\", err)", "Replace %v with %w", "_ = fmt.Errorf(\"read: %w\", err)")

    fun testNoWrapSuggestionOutsideErrorfOrForNonErrors() {
        myFixture.enableInspections(GoPrintfInspection())
        myFixture.configureByText("main.go", "package main\n\nimport \"fmt\"\n\nfunc f(s string, err error) {\n\tfmt.Printf(\"%<caret>v\\n\", err)\n\t_ = fmt.Errorf(\"%v\", s)\n}\n")
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Replace %v") })
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("%v\", s") + 1)
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Replace %v") })
    }

    fun testNoReplaceFixWhenTheVerbIsAnEscape() {
        myFixture.enableInspections(GoPrintfInspection())
        myFixture.configureByText("main.go", "package main\n\nimport \"fmt\"\n\nfunc f(i int) {\n\tfmt.Printf(\"%\\x7<caret>3\\n\", i)\n}\n")
        val highlights = myFixture.doHighlighting().map { it.description }
        assertTrue(highlights.toString(), "fmt.Printf format %s has arg i of wrong type int" in highlights)
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Replace %s") })
    }

    fun testSeveralWrapsAllowedSinceGo120() {
        assertFalse(GoPrintfChecker.multipleWrapsAllowed("1.19"))
        assertFalse(GoPrintfChecker.multipleWrapsAllowed("1.13.4"))
        assertTrue(GoPrintfChecker.multipleWrapsAllowed("1.20"))
        assertTrue(GoPrintfChecker.multipleWrapsAllowed("go1.22rc1"))
        assertTrue(GoPrintfChecker.multipleWrapsAllowed(null))
    }

    fun testOffWhenDiagnosticsComeFromAnotherSource() {
        val gate = object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project) = feature != GoIdeFeature.DIAGNOSTICS
        }
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, gate, testRootDisposable)
        myFixture.enableInspections(GoPrintfInspection())
        myFixture.configureByText("main.go", "package main\n\nimport \"fmt\"\n\nfunc f(s string) {\n\tfmt.Printf(\"%d\", s)\n}\n")
        assertEmpty(myFixture.doHighlighting().filter { it.description?.startsWith("fmt.Printf") == true })
    }

    fun testNoProblemsInGorootFiles() {
        val tool = GoPrintfInspection()
        val manager = InspectionManager.getInstance(project)
        val problems = ArrayList<String>()
        for (relative in listOf("fmt/print.go", "fmt/errors.go", "net/http/server.go", "go/types/errors.go", "log/log.go", "testing/testing.go", "cmd/go/internal/work/exec.go")) {
            val path = goroot().resolve("src").resolve(relative)
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: continue
            val file = PsiManager.getInstance(project).findFile(vf) as GoFile
            ProgressManager.getInstance().runProcess<List<com.intellij.codeInspection.ProblemDescriptor>>({ tool.processFile(file, manager) }, EmptyProgressIndicator()).forEach { d -> problems += "$relative:${d.lineNumber + 1}: ${d.descriptionTemplate}" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
