package io.github.golangsupport.ide.intentions

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Alt+Enter Go to Implementations / Interfaces / Method Specifications (GoLand parity G4): where they are offered, and a single target. */
class GoNavigationIntentionsTest : GoSemanticIdeTestBase() {

    private val shapes = """
        package p

        type Shape interface {
        	Area() float64
        }

        type Named interface {
        	Name() string
        }

        type Circle struct{ R float64 }

        func (c Circle) Area() float64 { return c.R }

        type Square struct{ S float64 }

        func (s Square) Area() float64 { return s.S }

        func (s Square) Name() string { return "square" }

        type Plain struct{}

        func (p Plain) other() {}
    """.trimIndent() + "\n"

    private fun offered(caretBefore: String, delta: Int = 0): List<String> {
        val text = shapes
        val at = text.indexOf(caretBefore).also { assertTrue(caretBefore, it >= 0) } + delta
        myFixture.configureByText("shapes.go", text.substring(0, at) + "<caret>" + text.substring(at))
        return myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Go to ") }
    }

    fun testImplementationsOnInterfaceAndItsMethods() {
        assertEquals(listOf("Go to Implementations"), offered("type Shape"))
        assertEquals(listOf("Go to Implementations"), offered("Shape interface", 2))
        assertEquals(listOf("Go to Implementations"), offered("Area() float64\n}"))
        // inside the body, away from a method name: nothing
        assertEquals(emptyList<String>(), offered("() float64\n}", 1))
    }

    fun testInterfacesOnConcreteTypes() {
        assertEquals(listOf("Go to Interfaces"), offered("type Circle"))
        assertEquals(listOf("Go to Interfaces"), offered("Square struct", 1))
        // implements nothing
        assertEquals(emptyList<String>(), offered("type Plain"))
    }

    fun testMethodSpecificationsOnImplementingMethods() {
        assertEquals(listOf("Go to Method Specifications"), offered("func (c Circle)"))
        assertEquals(listOf("Go to Method Specifications"), offered("Name() string {", 1))
        assertEquals(emptyList<String>(), offered("func (p Plain)"))
        // in the body of the method: nothing
        assertEquals(emptyList<String>(), offered("return c.R"))
    }

    fun testSingleTargetIsOpenedAtOnce() {
        offered("func (c Circle)")
        myFixture.launchAction(myFixture.findSingleIntention("Go to Method Specifications"))
        assertEquals(myFixture.file.text.indexOf("Area() float64\n}"), myFixture.caretOffset)

        offered("type Circle")
        myFixture.launchAction(myFixture.findSingleIntention("Go to Interfaces"))
        assertEquals(myFixture.file.text.indexOf("Shape interface"), myFixture.caretOffset)
    }

    fun testSingleImplementationInAnotherFileIsOpened() {
        myFixture.addFileToProject("impl.go", "package p\n\ntype Box struct{}\n\nfunc (Box) Size() int { return 0 }\n")
        myFixture.configureByText("iface.go", "package p\n\ntype <caret>Sizer interface {\n\tSize() int\n}\n")
        myFixture.launchAction(myFixture.findSingleIntention("Go to Implementations"))
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("impl.go", FileDocumentManager.getInstance().getFile(editor.document)?.name)
        assertEquals(editor.document.text.indexOf("Box struct"), editor.caretModel.offset)
    }
}
