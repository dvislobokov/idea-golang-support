package io.github.golangsupport

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoTypeNameMacro
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.settings.GoSettings

/** Declarations of the PSI as the tools of the plugin see them: kinds, values ([GoDeclarationInfo]), names, the declaration around an offset. */
class GoDeclarationInfoTest : BasePlatformTestCase() {
    private var languageServer = true

    // a file opened in an editor starts gopls, where it is installed: not in a test
    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
        } finally {
            super.tearDown()
        }
    }

    private val code = """
        package shop

        // Limit is the most an order holds.
        const Limit = 10

        var orders []Order

        type Order struct {
        	Total int
        }

        type Pricer interface {
        	Price(o Order) int
        }

        // Sum adds the totals.
        func Sum(items []Order) int {
        	count := 0
        	for _, o := range items {
        		count += o.Total
        	}
        	return count
        }

        func (o *Order) Add(n int) error {
        	o.Total += n
        	return nil
        }
    """.trimIndent()

    /** The identifier [name] where it stands in [context] (the first place of the code with that text): comments name things too. */
    private fun leaf(context: String, name: String = context): PsiElement {
        val start = code.indexOf(context)
        assertTrue("no $context", start >= 0 && context.contains(name))
        val leaf = myFixture.file.findElementAt(start + context.indexOf(name))!!
        assertEquals(context, GoTypes.IDENTIFIER, leaf.elementType)
        assertEquals(name, leaf.text)
        return leaf
    }

    private fun assertDeclaration(context: String, name: String, kind: GoDeclarationKind) {
        val declaration = GoDeclarationKind.ofName(leaf(context, name))
        assertNotNull("ofName($name)", declaration)
        assertEquals(name, declaration!!.name)
        assertEquals(name, kind, GoDeclarationKind.of(declaration))
        val info = GoDeclarationInfo.of(declaration)
        assertNotNull("of($name)", info)
        assertEquals(name, info!!.name)
        assertEquals(name, kind, info.kind)
        assertEquals(name, declaration.nameIdentifier!!.textRange, info.nameRange)
        // the range starts at the code, not at the doc comment
        assertFalse(name, code.substring(info.range.startOffset).startsWith("//"))
    }

    fun testKindsInfosAndNames() {
        myFixture.configureByText("shop.go", code)
        assertDeclaration("const Limit", "Limit", GoDeclarationKind.CONST)
        assertDeclaration("var orders", "orders", GoDeclarationKind.VAR)
        assertDeclaration("type Order", "Order", GoDeclarationKind.STRUCT)
        assertDeclaration("Total int", "Total", GoDeclarationKind.FIELD)
        assertDeclaration("type Pricer", "Pricer", GoDeclarationKind.INTERFACE)
        assertDeclaration("Price(o", "Price", GoDeclarationKind.INTERFACE_METHOD)
        assertDeclaration("func Sum", "Sum", GoDeclarationKind.FUNCTION)
        assertDeclaration("Add(n", "Add", GoDeclarationKind.METHOD)

        val sum = GoDeclarationKind.ofName(leaf("func Sum", "Sum")) as GoFunctionDeclaration
        assertEquals("(items []Order) int", GoDeclarationInfo.of(sum)!!.signature)
        assertTrue(code.substring(GoDeclarationInfo.of(sum)!!.body!!.startOffset).startsWith("{\n\tcount := 0"))
        val add = GoDeclarationKind.ofName(leaf("Add(n", "Add")) as GoMethodDeclaration
        assertEquals("Order", GoDeclarationInfo.of(add)!!.receiver)
        assertEquals("(n int) error", GoDeclarationInfo.of(add)!!.signature)
        assertEquals("(Order) Add(n int) error", GoDeclarationInfo.of(add)!!.presentation)
        assertEquals("[]Order", GoDeclarationInfo.of(GoDeclarationKind.ofName(leaf("var orders", "orders"))!!)!!.signature)
        assertEquals("(o Order) int", GoDeclarationInfo.of(GoDeclarationKind.ofName(leaf("Price(o", "Price"))!!)!!.signature)
    }

    fun testUsesAndLocalsHaveNoKind() {
        myFixture.configureByText("shop.go", code)
        // a use of a type is no declaration
        assertNull(GoDeclarationKind.ofName(leaf("[]Order", "Order")))
        // a local and a parameter have a named element of their own, but no kind
        val count = leaf("count := 0", "count")
        assertNull(GoDeclarationKind.ofName(count))
        assertNull(GoDeclarationKind.of(count.parent))
        assertNull(GoDeclarationKind.ofName(leaf("items []Order", "items")))
    }

    fun testDeclarationAroundAnOffset() {
        myFixture.configureByText("shop.go", code)
        val sum = GoDeclarationKind.at(myFixture.file, code.indexOf("count += o.Total"))
        assertTrue(sum is GoFunctionDeclaration)
        assertEquals("Sum", sum!!.name)
        assertEquals("Add", GoDeclarationKind.at(myFixture.file, code.indexOf("return nil"))!!.name)
        assertNull(GoDeclarationKind.at(myFixture.file, code.indexOf("package")))
    }

    fun testTopLevelInTheOrderOfTheText() {
        myFixture.configureByText("shop.go", code)
        val file = myFixture.file as GoFile
        assertEquals(listOf("Limit", "orders", "Order", "Pricer", "Sum", "Add"), GoDeclarationInfo.topLevel(file).map { it.name })
        assertEquals(listOf("Limit", "orders", "Order", "Total", "Pricer", "Price", "Sum", "Add"), GoDeclarationInfo.all(file).map { it.name })
        assertEquals("*Order", GoTypeNameMacro.receiverFor(file, code.length))
        assertEquals("*Order", GoTypeNameMacro.receiverFor(file, code.indexOf("type Pricer")))
        assertNull(GoTypeNameMacro.receiverFor(file, code.indexOf("type Order")))
    }

    fun testStructureAfterTyping() {
        myFixture.configureByText("shop.go", "$code\n\n<caret>")
        myFixture.type("func Extra(n int) {\n")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val extra = GoDeclarationInfo.topLevel(myFixture.file as GoFile).firstOrNull { it.name == "Extra" }
        assertNotNull(myFixture.file.text, extra)
        assertEquals(GoDeclarationKind.FUNCTION, extra!!.kind)
        assertEquals("(n int)", extra.signature)
    }
}
