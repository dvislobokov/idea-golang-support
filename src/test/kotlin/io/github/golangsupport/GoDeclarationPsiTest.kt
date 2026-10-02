package io.github.golangsupport

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarationPsi
import io.github.golangsupport.lang.GoStructure
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.settings.GoSettings

/** The bridge between the PSI of the parser and the text scanner: kinds, infos, names, the declaration around an offset. */
class GoDeclarationPsiTest : BasePlatformTestCase() {
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
        val declaration = GoDeclarationPsi.ofName(leaf(context, name))
        assertNotNull("ofName($name)", declaration)
        assertEquals(name, declaration!!.name)
        assertEquals(name, kind, GoDeclarationPsi.kindOf(declaration))
        val info = GoDeclarationPsi.infoOf(declaration)
        assertNotNull("infoOf($name)", info)
        assertEquals(name, info!!.name)
        assertEquals(name, kind, info.kind)
        assertSame(name, declaration, GoDeclarationPsi.psiOf(myFixture.file, info))
        assertFalse(name, GoDeclarationPsi.isLocalName(declaration.nameIdentifier!!))
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

        val sum = GoDeclarationPsi.ofName(leaf("func Sum", "Sum")) as GoFunctionDeclaration
        assertEquals("(items []Order) int", GoDeclarationPsi.infoOf(sum)!!.signature)
        val add = GoDeclarationPsi.ofName(leaf("Add(n", "Add")) as GoMethodDeclaration
        assertEquals("Order", add.receiverTypeName)
        assertEquals("Order", GoDeclarationPsi.infoOf(add)!!.receiver)
        assertEquals("(n int) error", GoDeclarationPsi.infoOf(add)!!.signature)
    }

    fun testUsesAndLocals() {
        myFixture.configureByText("shop.go", code)
        // a use of a type is no declaration
        assertNull(GoDeclarationPsi.ofName(leaf("[]Order", "Order")))
        // a local, its use and a parameter are local names, and have a named PSI element of their own
        val count = leaf("count := 0", "count")
        assertNull(GoDeclarationPsi.ofName(count))
        assertNull(GoDeclarationPsi.kindOf(count.parent))
        assertTrue(GoDeclarationPsi.isLocalName(count))
        assertNotNull(GoDeclarationPsi.namedOf(count))
        assertTrue(GoDeclarationPsi.isLocalName(leaf("count += o", "count")))
        assertTrue(GoDeclarationPsi.isLocalName(leaf("items []Order", "items")))
        // a use of a package-level name is not its declaration either, but no local
        assertNull(GoDeclarationPsi.namedOf(leaf("o.Total", "Total")))
    }

    fun testDeclarationAroundAnOffset() {
        myFixture.configureByText("shop.go", code)
        val inBody = code.indexOf("count += o.Total")
        val sum = GoDeclarationPsi.at(myFixture.file, inBody)
        assertTrue(sum is GoFunctionDeclaration)
        assertEquals("Sum", sum!!.name)
        assertEquals("Add", GoDeclarationPsi.at(myFixture.file, code.indexOf("return nil"))!!.name)
        assertNull(GoDeclarationPsi.at(myFixture.file, code.indexOf("package")))
    }

    fun testStructureAfterTyping() {
        myFixture.configureByText("shop.go", "$code\n\n<caret>")
        myFixture.type("func Extra(n int) {\n")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val structure = GoStructure.of(myFixture.file)
        val extra = structure.declarations.firstOrNull { it.name == "Extra" }
        assertNotNull(myFixture.file.text, extra)
        assertEquals(GoDeclarationKind.FUNCTION, extra!!.kind)
        val psi = GoDeclarationPsi.psiOf(myFixture.file, extra)
        assertTrue("$psi", psi is GoFunctionDeclaration)
        assertEquals(extra.nameRange.startOffset, GoDeclarationPsi.infoOf(psi!!)!!.nameRange.startOffset)
    }
}
