package io.github.golangsupport.lang.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoCodeInsightTestBase

/** Names, name identifiers and renaming of every named element kind (AST-backed PSI). */
class GoNamedElementsTest : GoCodeInsightTestBase() {

    private val source = """
        package pkg

        import (
        	"fmt"
        	str "strings"
        	. "math"
        	_ "embed"
        	"golang.org/x/tools/go/packages"
        )

        type List[T any] struct {
        	*Node
        	pkg.Embedded
        	a, b int
        }

        type I interface{ Do(x int) }

        func (l *List[T]) Len() int { return 0 }
        func (*List[T]) Cap() int { return 0 }

        func F(p, q int) {
        loop:
        	for {
        		break loop
        	}
        }

        var V = 1
        const C = 2
    """.trimIndent()

    private fun file(): GoFile = myFixture.configureByText("pkg.go", source) as GoFile

    private inline fun <reified T : GoNamedElement> names(file: GoFile): List<String?> =
        PsiTreeUtil.findChildrenOfType(file, T::class.java).map { it.name }

    fun testNames() {
        val file = file()
        assertEquals(listOf("pkg"), names<GoPackageClause>(file))
        assertEquals(listOf("fmt", "str", ".", "_", "packages"), names<GoImportSpec>(file))
        assertEquals(listOf("Node", "Embedded"), names<GoAnonymousFieldDefinition>(file))
        assertEquals(listOf("a", "b"), names<GoFieldDefinition>(file))
        assertEquals(listOf("T"), names<GoTypeParamDefinition>(file))
        assertEquals(listOf("List", "I"), names<GoTypeSpec>(file))
        assertEquals(listOf("Do"), names<GoMethodSpec>(file))
        assertEquals(listOf("l", null), names<GoReceiver>(file))
        assertEquals(listOf("Len", "Cap"), names<GoMethodDeclaration>(file))
        assertEquals(listOf("F"), names<GoFunctionDeclaration>(file))
        assertEquals(listOf("x", "p", "q"), names<GoParamDefinition>(file))
        assertEquals(listOf("loop"), names<GoLabelDefinition>(file))
        assertEquals(listOf("V"), names<GoVarDefinition>(file))
        assertEquals(listOf("C"), names<GoConstDefinition>(file))

        val imports = file.imports
        assertEquals(listOf(null, "str", ".", "_", null), imports.map { it.alias })
        assertEquals(listOf(false, false, true, false, false), imports.map { it.isDot })
        assertEquals(listOf(false, false, false, true, false), imports.map { it.isBlank })
        assertEquals("golang.org/x/tools/go/packages", imports.last().path)
        assertEquals(listOf("List", "List"), file.methods.map { it.receiverTypeName })
        assertEquals(listOf(true, true), file.methods.map { it.isPointerReceiver })
        assertTrue(file.functions.single().isPublic())
        assertFalse(names<GoFieldDefinition>(file).isEmpty())
        assertEquals("Node", PsiTreeUtil.findChildOfType(file, GoAnonymousFieldDefinition::class.java)!!.nameIdentifier!!.text)
    }

    fun testSetName() {
        val file = file()
        WriteCommandAction.runWriteCommandAction(project) {
            file.functions.single().setName("G")
            file.imports[1].setName("s2")
            file.imports[0].setName("f")
            PsiTreeUtil.findChildOfType(file, GoLabelDefinition::class.java)!!.setName("outer")
            PsiTreeUtil.findChildOfType(file, GoAnonymousFieldDefinition::class.java)!!.setName("Leaf")
        }
        val text = file.text
        assertTrue(text, text.contains("func G(p, q int)"))
        assertTrue(text, text.contains("s2 \"strings\""))
        assertTrue(text, text.contains("f \"fmt\""))
        assertTrue(text, text.contains("outer:"))
        assertTrue(text, text.contains("*Leaf"))
        assertEquals("f", file.imports[0].name)
    }
}
