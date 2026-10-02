package io.github.golangsupport.ide

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.navigation.GoTypeDeclarationProvider
import io.github.golangsupport.ide.usages.GoFindUsagesHandlerFactory
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec

/** A closed gate: every navigation and usages extension of go-psi-ide stands down; the default gate lets everything through. */
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

    fun testClosedGateFindUsagesOfMethod() {
        myFixture.configureByText("shapes.go", shapes)
        val method = myFixture.findElementByText("Area() float64 { return", GoMethodDeclaration::class.java)
        val factory = FindUsagesHandlerFactory.EP_NAME.getExtensions(project).filterIsInstance<GoFindUsagesHandlerFactory>().single()
        assertTrue(factory.canFindUsages(method))
        close(GoIdeFeature.USAGES)
        assertFalse(factory.canFindUsages(method))
    }
}
