package io.github.golangsupport.lang.psi

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoCodeInsightTestBase

/** `GoNamedElement.docComment` / `docText` and `GoFile.packageDoc`. */
class GoDocCommentsTest : GoCodeInsightTestBase() {

    private val source = """
        // Copyright header.

        // Package pkg does things.
        //
        // Second paragraph.
        //go:generate echo hi
        package pkg

        // F is documented.
        func F() {}

        // detached

        func NoDoc() {}

        // M is a method.
        func (T) M() {}

        // T is a type.
        type T struct {
        	// A is a field.
        	A int
        	B int // trailing, not a doc
        	/* C block doc */
        	C int
        	// Emb is embedded.
        	Emb
        }

        // Group doc.
        type (
        	// X has own doc.
        	X int
        	Y int
        )

        // Vars doc.
        var (
        	// P own.
        	P = 1
        	Q = 2
        )

        // One is single.
        const One = 1

        const (
        	// Two own.
        	Two = 2
        	Three = 3
        )

        type I interface {
        	// Do does.
        	Do(x int)
        	Undoc()
        }

        func G(p int) { L: for {} }
    """.trimIndent()

    private fun file(): GoFile = myFixture.configureByText("pkg.go", source) as GoFile

    private inline fun <reified T : GoNamedElement> byName(file: GoFile, name: String): T =
        PsiTreeUtil.findChildrenOfType(file, T::class.java).first { it.name == name }

    private fun doc(e: GoNamedElement): String? = e.docText

    fun testPackageDoc() {
        val f = file()
        assertEquals("// Package pkg does things.", f.packageDoc?.text)
        assertEquals("Package pkg does things.\n\nSecond paragraph.\n", f.packageDocText)
    }

    fun testFunctionsAndMethods() {
        val f = file()
        assertEquals("F is documented.\n", doc(byName<GoFunctionDeclaration>(f, "F")))
        assertEquals("// F is documented.", byName<GoFunctionDeclaration>(f, "F").docComment?.text)
        assertNull(byName<GoFunctionDeclaration>(f, "NoDoc").docComment)
        assertNull(doc(byName<GoFunctionDeclaration>(f, "NoDoc")))
        assertEquals("M is a method.\n", doc(byName<GoMethodDeclaration>(f, "M")))
    }

    fun testFields() {
        val f = file()
        assertEquals("A is a field.\n", doc(byName<GoFieldDefinition>(f, "A")))
        assertNull(doc(byName<GoFieldDefinition>(f, "B")))
        assertEquals(" C block doc", byName<GoFieldDefinition>(f, "C").docText?.trimEnd('\n'))
        assertEquals("Emb is embedded.\n", doc(byName<GoAnonymousFieldDefinition>(f, "Emb")))
    }

    fun testTypeGroups() {
        val f = file()
        assertEquals("T is a type.\n", doc(byName<GoTypeSpec>(f, "T")))
        assertEquals("X has own doc.\n", doc(byName<GoTypeSpec>(f, "X")))
        assertEquals("Group doc.\n", doc(byName<GoTypeSpec>(f, "Y")))
    }

    fun testVarsAndConsts() {
        val f = file()
        assertEquals("P own.\n", doc(byName<GoVarDefinition>(f, "P")))
        assertEquals("Vars doc.\n", doc(byName<GoVarDefinition>(f, "Q")))
        assertEquals("One is single.\n", doc(byName<GoConstDefinition>(f, "One")))
        assertEquals("Two own.\n", doc(byName<GoConstDefinition>(f, "Two")))
        assertNull(doc(byName<GoConstDefinition>(f, "Three")))
    }

    fun testInterfaceMethodsAndNonDocElements() {
        val f = file()
        assertEquals("Do does.\n", doc(byName<GoMethodSpec>(f, "Do")))
        assertNull(doc(byName<GoMethodSpec>(f, "Undoc")))
        assertNull(doc(byName<GoParamDefinition>(f, "p")))
        assertNull(doc(byName<GoLabelDefinition>(f, "L")))
    }
}
