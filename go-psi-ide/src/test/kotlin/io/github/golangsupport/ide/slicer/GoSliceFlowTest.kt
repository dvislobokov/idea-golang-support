package io.github.golangsupport.ide.slicer

import com.intellij.analysis.AnalysisScope
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.slicer.SliceAnalysisParams
import com.intellij.slicer.SliceUsage
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDefinition

/** Analyze | Data Flow to / from Here over a small fixture: one level of [GoSliceFlow] per step, and the slice usages of the provider. */
class GoSliceFlowTest : GoSemanticIdeTestBase() {
    private lateinit var file: GoFile
    private val text = """
        package p

        func produce() int { return 42 }

        func consume(v int) int { return v * 2 }

        func main() {
        	a := produce()
        	b := 1
        	if a > 0 {
        		b = a
        	}
        	c := consume(b)
        	_ = c
        }
    """.trimIndent() + "\n"

    override fun setUp() {
        super.setUp()
        file = myFixture.addFileToProject("slice/main.go", text) as GoFile
    }

    private val scope get() = GlobalSearchScope.projectScope(project)

    /** The element of [T] at the [occurrence]-th match of [marker] (the caret at the start of the match plus [shift]). */
    private inline fun <reified T : PsiElement> at(marker: String, shift: Int = 0, occurrence: Int = 0): T {
        var offset = -1
        repeat(occurrence + 1) { offset = text.indexOf(marker, offset + 1) }
        assertTrue("no $marker", offset >= 0)
        var e: PsiElement? = file.findElementAt(offset + shift)
        while (e != null && e !is T) e = e.parent
        return e as T
    }

    private fun texts(steps: List<GoSliceStep>): List<String> = steps.map { it.element.text }

    fun testAReadIsFedByTheWritesThatReachIt() {
        val read = at<GoReferenceExpression>("consume(b)", shift = 8)
        assertEquals(listOf("1", "a"), texts(GoSliceFlow.sources(read, scope)).sorted())
    }

    fun testACallIsFedByTheReturnsOfItsFunction() {
        val a = at<GoReferenceExpression>("b = a", shift = 4)
        assertEquals(listOf("produce()"), texts(GoSliceFlow.sources(a, scope)))
        assertEquals(listOf("42"), texts(GoSliceFlow.sources(GoSliceFlow.sources(a, scope).single().element, scope)))
    }

    fun testAParameterIsFedByTheArgumentsOfItsCalls() {
        val v = at<GoParamDefinition>("v int")
        assertEquals(listOf("b"), texts(GoSliceFlow.sources(v, scope)))
    }

    fun testADeclarationIsFedByItsInitializerAndItsAssignments() {
        val b = at<GoVarDefinition>("b := 1")
        assertEquals(listOf("1", "a"), texts(GoSliceFlow.sources(b, scope)))
    }

    fun testAValueGoesToTheVariableItIsAssignedTo() {
        val a = at<GoReferenceExpression>("b = a", shift = 4)
        val target = GoSliceFlow.consumers(a, scope).single().element
        assertEquals("b", target.text)
        // the write reaches the read of the call below the if
        assertEquals(listOf("b"), texts(GoSliceFlow.consumers(target, scope)))
        assertEquals(text.indexOf("consume(b)") + 8, GoSliceFlow.consumers(target, scope).single().element.textOffset)
    }

    fun testAnArgumentGoesToTheParameterAndAReturnToTheCalls() {
        val argument = at<GoReferenceExpression>("consume(b)", shift = 8)
        val parameter = GoSliceFlow.consumers(argument, scope).single().element
        assertTrue(parameter is GoParamDefinition)
        val read = GoSliceFlow.consumers(parameter, scope).single().element
        assertEquals("v", read.text)
        // v * 2 is returned: it goes to the calls of consume, and the call to c
        val returned = read.parent
        val calls = GoSliceFlow.consumers(returned, scope)
        assertEquals(listOf("consume(b)"), texts(calls))
        assertEquals(listOf("c"), texts(GoSliceFlow.consumers(calls.single().element, scope)))
    }

    fun testTheTargetIsAVariableOrAParameter() {
        assertTrue(GoSliceFlow.target(file.findElementAt(text.indexOf("consume(b)") + 8)!!) is GoReferenceExpression)
        assertTrue(GoSliceFlow.target(file.findElementAt(text.indexOf("v int"))!!) is GoParamDefinition)
        assertNull("a function is not a value to slice", GoSliceFlow.target(file.findElementAt(text.indexOf("produce()", text.indexOf("a :=")))!!))
    }

    fun testTheProviderGivesOneLevelPerNode() {
        val read = at<GoReferenceExpression>("consume(b)", shift = 8)
        val params = SliceAnalysisParams().apply { dataFlowToThis = true; scope = AnalysisScope(project) }
        val root = GoSliceProvider().createRootUsage(read, params)
        val children = mutableListOf<SliceUsage>()
        // the tree asks for the children under a progress, in the background
        ProgressManager.getInstance().runProcess({ root.processChildren { children += it; true } }, EmptyProgressIndicator())
        assertEquals(listOf("1", "a"), children.map { it.element!!.text }.sorted())
        assertTrue(children.all { it is GoSliceUsage && it.parent === root })
    }
}
