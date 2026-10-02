package io.github.golangsupport.ide

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.lang.ImportOptimizer
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.util.ThreeState
import io.github.golangsupport.ide.completion.GoCompletionConfidence
import io.github.golangsupport.ide.completion.GoCompletionContributor
import io.github.golangsupport.ide.completion.GoCompletionWeigher
import io.github.golangsupport.ide.documentation.GoDocumentationTargetProvider
import io.github.golangsupport.ide.documentation.GoExpressionTypeProvider
import io.github.golangsupport.ide.documentation.GoParameterInfoHandler
import io.github.golangsupport.ide.inspections.GoImportOptimizer
import io.github.golangsupport.ide.inspections.GoInspectionClasses
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.ide.navigation.GoTypeDeclarationProvider
import io.github.golangsupport.ide.usages.GoFindUsagesHandlerFactory
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec

/** A closed gate: every navigation, usages, completion, documentation and diagnostics extension of go-psi-ide stands down; the default gate lets everything through. */
class GoIdeFeatureGateTest : GoSemanticIdeTestBase() {

    private val shapes = """
        package p

        type Shape interface {
            Area() float64
        }

        type Circle struct{ R float64 }

        func (c *Circle) Area() float64 { return c.R }

        var s Shape = &Circle{}
    """.trimIndent()

    private fun close(vararg features: GoIdeFeature) {
        val closed = features.toSet()
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature !in closed
        }, testRootDisposable)
    }

    fun testTheDefaultGateLetsEverythingThrough() {
        for (feature in GoIdeFeature.entries) assertTrue(feature.name, GoIdeFeatureGate.enabled(feature, project))
        myFixture.configureByText("shapes.go", shapes)
        val shape = myFixture.findElementByText("Shape", GoTypeSpec::class.java)
        assertEquals(1, DefinitionsScopedSearch.search(shape).findAll().size)
    }

    fun testClosedGateNavigation() {
        close(GoIdeFeature.NAVIGATION)
        myFixture.configureByText("shapes.go", shapes)
        val shape = myFixture.findElementByText("Shape", GoTypeSpec::class.java)
        assertEmpty(DefinitionsScopedSearch.search(shape).findAll())
        val provider = TypeDeclarationProvider.EP_NAME.extensionList.filterIsInstance<GoTypeDeclarationProvider>().single()
        myFixture.configureByText("t.go", "package p\n\ntype T struct{}\n\nvar v<caret>alue T\n")
        assertNull(provider.getSymbolTypeDeclarations(myFixture.elementAtCaret))
    }

    fun testClosedGateImplementationMarkers() {
        close(GoIdeFeature.IMPLEMENTATION_MARKERS)
        myFixture.configureByText("shapes.go", shapes)
        assertEmpty(myFixture.findAllGutters().mapNotNull { it.tooltipText })
    }

    fun testClosedGateUsages() {
        close(GoIdeFeature.USAGES)
        myFixture.configureByText("shapes.go", shapes)
        val method = myFixture.findElementByText("Area() float64 { return", GoMethodDeclaration::class.java)
        val factory = FindUsagesHandlerFactory.EP_NAME.getExtensions(project).filterIsInstance<GoFindUsagesHandlerFactory>().single()
        assertFalse(factory.canFindUsages(method))
    }

    fun testClosedGateCompletion() {
        val body = "package p\n\nfunc main() {\n\tvalue := 1\n\t<caret>\n}\n"
        myFixture.configureByText("c.go", body)
        assertTrue("the open gate offers the local and the keywords", myFixture.completeBasic().orEmpty().any { GoCompletionWeigher.infoOf(it) != null })
        close(GoIdeFeature.COMPLETION)
        myFixture.configureByText("d.go", body)
        val items = myFixture.completeBasic().orEmpty().filter { GoCompletionWeigher.infoOf(it) != null }
        assertEmpty("no item of the Go contributor: " + items.map { it.lookupString }, items)
        // the confidence of the Go contributor says nothing either: in a comment it would have skipped the popup
        myFixture.configureByText("e.go", "package p\n\n// a c<caret>omment\n")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertEquals(ThreeState.UNSURE, GoCompletionConfidence().shouldSkipAutopopup(myFixture.editor, element, myFixture.file, myFixture.caretOffset))
    }

    fun testACodeFragmentIsNotCompleted() {
        myFixture.configureByText("f.go", "package p\n")
        assertFalse("a file of the project", GoCompletionContributor.isCodeFragment(myFixture.file))
        for (physical in listOf(true, false)) {
            val fragment = PsiFileFactory.getInstance(project).createFileFromText("expression.go", GoLanguage, "x.y", physical, true)
            assertTrue("physical=$physical", GoCompletionContributor.isCodeFragment(fragment))
        }
    }

    fun testClosedGateHover() {
        myFixture.configureByText("shapes.go", shapes)
        val shape = myFixture.file.findReferenceAt(shapes.indexOf("Shape = &") + 1)!!.resolve()!!
        assertTrue(shape is GoTypeSpec)
        val provider = PsiDocumentationTargetProvider.EP_NAME.extensionList.filterIsInstance<GoDocumentationTargetProvider>().single()
        assertNotNull(provider.documentationTarget(shape, null))
        close(GoIdeFeature.HOVER)
        assertNull(provider.documentationTarget(shape, null))
        myFixture.configureByText("t.go", "package p\n\nfunc f(a int) int { return f(a<caret>) }\n")
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        assertEmpty(GoExpressionTypeProvider().getExpressionsAt(leaf))
        assertNull(GoParameterInfoHandler().findElementForParameterInfo(MockCreateParameterInfoContext(myFixture.editor, myFixture.file)))
    }

    private val broken = """
        package p

        import "os"

        func f() int { return undefinedName }
    """.trimIndent()

    private fun inspectionProblems(): List<String> {
        myFixture.enableInspections(*GoInspectionClasses.ALL.map { it.getDeclaredConstructor().newInstance() }.toTypedArray())
        myFixture.configureByText("broken.go", broken)
        return myFixture.doHighlighting().mapNotNull { it.inspectionToolId?.let { id -> "$id: ${it.description}" } }
    }

    private fun goImportOptimizer(): ImportOptimizer =
        LanguageImportStatements.INSTANCE.allForLanguage(myFixture.file.language).filterIsInstance<GoImportOptimizer>().single()

    /** DIAGNOSTICS off: the inspections report nothing and the import optimizer declines the file; the default gate reports both problems. */
    fun testClosedGateDiagnostics() {
        close(GoIdeFeature.DIAGNOSTICS)
        assertEmpty(inspectionProblems())
        assertFalse(goImportOptimizer().supports(myFixture.file))
    }

    fun testTheDefaultGateReportsDiagnostics() {
        val problems = inspectionProblems()
        assertTrue(problems.toString(), problems.any { it.startsWith("GoUnresolvedReference: ") && "undefinedName" in it })
        assertTrue(problems.toString(), problems.any { it.startsWith("GoUnusedImport: ") && "\"os\"" in it })
        assertTrue(goImportOptimizer().supports(myFixture.file))
    }

    fun testClosedGateFindUsagesOfMethod() {
        myFixture.configureByText("shapes.go", shapes)
        val method = myFixture.findElementByText("Area() float64 { return", GoMethodDeclaration::class.java)
        val factory = FindUsagesHandlerFactory.EP_NAME.getExtensions(project).filterIsInstance<GoFindUsagesHandlerFactory>().single()
        assertTrue(factory.canFindUsages(method))
        close(GoIdeFeature.USAGES)
        assertFalse(factory.canFindUsages(method))
    }
}
