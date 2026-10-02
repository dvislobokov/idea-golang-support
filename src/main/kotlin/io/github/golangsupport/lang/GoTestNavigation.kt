package io.github.golangsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testIntegration.TestCreator
import com.intellij.testIntegration.TestFinder
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration

/** How the tests of Go are named after what they test: `x_test.go` for `x.go`, `TestF` and `TestT_F` for `F` and `(T) F`. Pure. */
object GoTestNames {
    const val TEST_SUFFIX = "_test.go"

    private val PREFIXES = listOf("Test", "Benchmark", "Example", "Fuzz")

    fun testFileName(fileName: String): String = fileName.removeSuffix(".go") + TEST_SUFFIX
    fun sourceFileName(testFileName: String): String = testFileName.removeSuffix(TEST_SUFFIX) + ".go"

    /**
     * Whether [testName] tests [name] (a method of [receiver]): `TestF`, `Test_f` (gotests writes it so for an unexported name),
     * `TestT_F`, `BenchmarkF`, `ExampleF`, `FuzzF`, and a sub-name of any of them (`TestF_empty`).
     */
    fun isTestOf(testName: String, name: String, receiver: String?): Boolean {
        val prefix = PREFIXES.firstOrNull { testName.startsWith(it) } ?: return false
        val rest = testName.removePrefix(prefix)
        val subjects = listOfNotNull(name, "_$name", receiver?.let { "${it}_$name" }, receiver?.let { "_${it}_$name" })
        return subjects.any { rest == it || rest.startsWith("${it}_") }
    }

    /** What a test may be a test of, as (name, receiver): `TestOrder_Total` is `Total` of `Order` or the function `Order_Total`; `Test_calc` is `calc`. */
    fun subjectsOf(testName: String): List<Pair<String, String?>> {
        val prefix = PREFIXES.firstOrNull { testName.startsWith(it) } ?: return emptyList()
        val rest = testName.removePrefix(prefix).removePrefix("_")
        if (rest.isEmpty()) return emptyList()
        val result = ArrayList<Pair<String, String?>>()
        result += rest to null
        val parts = rest.split('_')
        if (parts.size >= 2 && parts.all { it.isNotEmpty() }) {
            result += parts[1] to parts[0]
            // a sub-name after the function: `TestOrder_Total_empty`, `TestTotal_empty`
            result += parts[0] to null
        }
        return result.distinct()
    }
}

/**
 * Navigate | Test (Ctrl+Shift+T) and back: from `x.go` to `x_test.go` and from a function to its tests by name; from a test to what it
 * tests. Without a test file the platform offers to create one: [GoTestCreator], the Generate Test of the plugin.
 */
class GoTestFinder : TestFinder {
    override fun findSourceElement(from: PsiElement): PsiElement? {
        val file = from.containingFile as? GoFile ?: return null
        val declaration = if (from is GoFile) null else GoDeclarationKind.at(file, from.textRange.startOffset)
        return declaration?.takeIf { it is GoFunctionOrMethodDeclaration } ?: file
    }

    override fun isTest(element: PsiElement): Boolean = (element.containingFile as? GoFile)?.isTestFile == true

    override fun findTestsForClass(element: PsiElement): Collection<PsiElement> {
        val file = element.containingFile as? GoFile ?: return emptyList()
        val testFile = sibling(file, GoTestNames.testFileName(file.name)) ?: return emptyList()
        val function = element as? GoFunctionOrMethodDeclaration ?: return listOf(testFile)
        val name = function.name ?: return listOf(testFile)
        val receiver = (function as? GoMethodDeclaration)?.receiverTypeName
        return testFile.functions.filter { test -> test.name?.let { GoTestNames.isTestOf(it, name, receiver) } == true }.ifEmpty { listOf(testFile) }
    }

    override fun findClassesForTest(element: PsiElement): Collection<PsiElement> {
        val file = element.containingFile as? GoFile ?: return emptyList()
        val source = sibling(file, GoTestNames.sourceFileName(file.name)) ?: return emptyList()
        val test = (element as? GoFunctionOrMethodDeclaration)?.name ?: return listOf(source)
        val subjects = GoTestNames.subjectsOf(test)
        val functions = source.functions.filter { f -> subjects.any { (name, receiver) -> receiver == null && f.name == name } }
        val methods = source.methods.filter { m -> subjects.any { (name, receiver) -> receiver != null && m.name == name && m.receiverTypeName == receiver } }
        // in the order of the file, as the declarations stand
        return (functions + methods).sortedBy { it.textOffset }.ifEmpty { listOf(source) }
    }

    override fun navigateToTestImmediately(source: PsiElement): Boolean = true

    private fun sibling(file: GoFile, name: String): GoFile? =
        file.virtualFile?.parent?.findChild(name)?.let { PsiManager.getInstance(file.project).findFile(it) as? GoFile }
}

/** "Create New Test" of Navigate | Test when there is none: the table-driven test of Alt+Insert. */
class GoTestCreator : TestCreator, DumbAware {
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        file is GoFile && !file.isTestFile && GenerateContext(project, editor, file).functionAtCaret != null

    override fun createTest(project: Project, editor: Editor, file: PsiFile) {
        if (file is GoFile) GoGenerateTestAction.generate(GenerateContext(project, editor, file))
    }
}
