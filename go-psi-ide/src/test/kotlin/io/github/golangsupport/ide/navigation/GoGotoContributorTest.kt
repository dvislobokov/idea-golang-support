package io.github.golangsupport.ide.navigation

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.lang.psi.GoNamedElement

class GoGotoContributorTest : GoIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "shapes/shape.go",
            """
            package shapes

            type Shape interface{ Area() float64 }

            type point struct{ x, y int }

            func NewPoint() *point { return nil }

            func (p *point) Area() float64 { return 0 }

            const Pi = 3.14

            var registry = map[string]Shape{}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "util/util.go",
            """
            package util

            type Alias = int

            func helper() {
            	type local int
            	var inner int
            	_ = inner
            }
            """.trimIndent(),
        )
    }

    fun testRegistered() {
        assertTrue(ChooseByNameContributor.SYMBOL_EP_NAME.extensionList.any { it is GoGotoSymbolContributor })
        assertTrue(ChooseByNameContributor.CLASS_EP_NAME.extensionList.any { it is GoGotoClassContributor })
    }

    fun testSymbolNames() {
        val contributor = GoGotoSymbolContributor()
        val names = names(contributor)
        assertTrue(names.toString(), names.containsAll(listOf("Shape", "point", "NewPoint", "Area", "Pi", "registry", "Alias", "helper")))
        // names may hold stale keys of the persistent test index (other tests' top-level `local`), so the elements decide
        assertTrue("locals are not symbols", elements(contributor, "local").isEmpty() && elements(contributor, "inner").isEmpty())
    }

    fun testSymbolElements() {
        val contributor = GoGotoSymbolContributor()
        assertEquals(listOf("NewPoint"), elements(contributor, "NewPoint").map { (it as GoNamedElement).name })
        assertEquals(listOf("registry"), elements(contributor, "registry").map { (it as GoNamedElement).name })
        val method = elements(contributor, "Area").single() as GoNamedElement
        val presentation = ItemPresentationProviders.getItemPresentation(method)!!
        assertEquals("point.Area", presentation.presentableText)
        assertEquals("shapes (shapes/shape.go)", presentation.locationString)
        assertSame(GoIdeIcons.METHOD, presentation.getIcon(false))
    }

    fun testClassNamesAndElements() {
        val contributor = GoGotoClassContributor()
        val names = names(contributor)
        assertTrue(names.toString(), names.containsAll(listOf("Shape", "point", "Alias")))
        assertFalse(names.contains("NewPoint") || names.contains("local"))
        val shape = elements(contributor, "Shape").single()
        assertEquals("shapes.Shape", contributor.getQualifiedName(shape))
        assertSame(GoIdeIcons.INTERFACE, ItemPresentationProviders.getItemPresentation(shape)!!.getIcon(false))
        assertSame(GoIdeIcons.STRUCT, ItemPresentationProviders.getItemPresentation(elements(contributor, "point").single())!!.getIcon(false))
    }

    private fun names(contributor: GoGotoContributorBase<*>): Set<String> {
        val processor = CommonProcessors.CollectProcessor<String>()
        contributor.processNames(processor, GlobalSearchScope.allScope(project), null)
        return processor.results.toSet()
    }

    private fun elements(contributor: GoGotoContributorBase<*>, name: String): List<NavigationItem> {
        val processor = CommonProcessors.CollectProcessor<NavigationItem>()
        contributor.processElementsWithName(name, processor, FindSymbolParameters.wrap(name, project, true))
        return processor.results.toList()
    }
}
