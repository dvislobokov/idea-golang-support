package io.github.golangsupport.ide.usages

import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoVarDefinition

/** Find Usages over `testData/usages/kinds`: counts and usage types per declaration kind, use scopes. */
class GoFindUsagesTest : GoSemanticIdeTestBase() {

    override val testDataSubdir: String = "usages"

    private lateinit var a: GoFile
    private lateinit var b: GoFile

    override fun setUp() {
        super.setUp()
        a = myFixture.addFileToProject("kinds/a.go", readTestData("kinds/a.go")) as GoFile
        b = myFixture.addFileToProject("kinds/b.go", readTestData("kinds/b.go")) as GoFile
    }

    private inline fun <reified T : GoNamedElement> decl(file: GoFile, name: String, occurrence: Int = 0): T =
        PsiTreeUtil.findChildrenOfType(file, T::class.java).filter { it.name == name }[occurrence]

    private fun describe(usages: Collection<UsageInfo>): String = usages.mapNotNull { u ->
        val element = u.element ?: return@mapNotNull null
        val file = element.containingFile
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)!!
        val offset = u.navigationRange?.startOffset ?: element.textRange.startOffset
        val line = doc.getLineNumber(offset)
        val text = doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim()
        val type = GoUsageTypeProvider().getUsageType(element)
        "${file.name}:${line + 1} [$type] $text"
    }.sorted().joinToString("\n")

    private fun section(title: String, element: PsiElement): String = "== $title\n" + describe(myFixture.findUsages(element))

    fun testUsagesPerKind() {
        TestDialogManager.setTestDialog(TestDialog.NO) // methods: do not include interface methods here
        val report = listOf(
            section("type Point", decl<io.github.golangsupport.lang.psi.GoTypeSpec>(a, "Point")),
            section("type Base (embedded)", decl<io.github.golangsupport.lang.psi.GoTypeSpec>(a, "Base")),
            section("field X", decl<io.github.golangsupport.lang.psi.GoFieldDefinition>(a, "X")),
            section("field Y", decl<io.github.golangsupport.lang.psi.GoFieldDefinition>(a, "Y")),
            section("promoted field ID", decl<io.github.golangsupport.lang.psi.GoFieldDefinition>(a, "ID")),
            section("method Point.Move", decl<GoMethodDeclaration>(a, "Move")),
            section("interface method Mover.Move", decl<GoMethodSpec>(a, "Move")),
            section("func NewPoint", decl<io.github.golangsupport.lang.psi.GoFunctionDeclaration>(a, "NewPoint")),
            section("unexported func helper", decl<io.github.golangsupport.lang.psi.GoFunctionDeclaration>(a, "helper")),
            section("local p", decl<GoVarDefinition>(a, "p")),
            section("local i", decl<GoVarDefinition>(a, "i")),
            section("label outer", decl<GoLabelDefinition>(a, "outer")),
            section("import fmt", decl<GoImportSpec>(a, "fmt")),
            section("import math/rand/v2", PsiTreeUtil.findChildrenOfType(a, GoImportSpec::class.java).single { it.path == "math/rand/v2" }),
        ).joinToString("\n\n")
        assertGolden("kinds/usages.txt", report)
    }

    fun testMethodUsagesIncludeInterfaceMethodOnRequest() {
        val move = decl<GoMethodDeclaration>(a, "Move")
        TestDialogManager.setTestDialog(TestDialog.NO)
        val only = myFixture.findUsages(move).size
        TestDialogManager.setTestDialog(TestDialog.OK)
        val withInterface = myFixture.findUsages(move)
        // `m.Move(3)` resolves to Mover.Move and is found only with the interface method included.
        assertEquals(only + 1, withInterface.size)
        assertTrue(withInterface.any { it.element?.text == "m.Move" })
    }

    fun testUsageOfGorootFunctionFromProject() {
        val call = PsiTreeUtil.findChildrenOfType(a, io.github.golangsupport.lang.psi.GoReferenceExpression::class.java).first { it.text == "fmt.Println" }
        val println = call.reference!!.resolve()!!
        assertTrue(println.containingFile.virtualFile.path.endsWith("/src/fmt/print.go"))
        val usages = ReferencesSearch.search(println, GlobalSearchScope.projectScope(project)).findAll()
        assertEquals(listOf("fmt.Println"), usages.map { it.element.text })
    }

    fun testUseScopes() {
        val local = decl<GoVarDefinition>(a, "p")
        assertInstanceOf(local.useScope, LocalSearchScope::class.java)
        assertInstanceOf(decl<GoLabelDefinition>(a, "outer").useScope, LocalSearchScope::class.java)
        assertInstanceOf(decl<GoImportSpec>(a, "fmt").useScope, LocalSearchScope::class.java)
        val param = PsiTreeUtil.findChildrenOfType(a, io.github.golangsupport.lang.psi.GoParamDefinition::class.java).first { it.name == "dx" }
        assertInstanceOf(param.useScope, LocalSearchScope::class.java)
        // Unexported package-level: the package directory only.
        val helper = decl<io.github.golangsupport.lang.psi.GoFunctionDeclaration>(a, "helper")
        val helperScope = helper.useScope as GlobalSearchScope
        assertTrue(helperScope.contains(b.virtualFile))
        val elsewhere = myFixture.addFileToProject("other/c.go", "package other\n\nfunc helper() {}\n")
        assertFalse(helperScope.contains(elsewhere.virtualFile))
        // Exported: project and libraries.
        val newPoint = decl<io.github.golangsupport.lang.psi.GoFunctionDeclaration>(a, "NewPoint")
        assertTrue((newPoint.useScope as GlobalSearchScope).contains(elsewhere.virtualFile))
        // A same-named local in another file is not a usage of the field.
        val inOther = ReferencesSearch.search(
            decl<io.github.golangsupport.lang.psi.GoFieldDefinition>(a, "X"),
            LocalSearchScope(decl<io.github.golangsupport.lang.psi.GoFunctionDeclaration>(b, "other")),
        ).findAll()
        assertEquals(listOf("NewPoint(2).X"), inOther.map { it.element.text })
    }
}
