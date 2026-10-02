package io.github.golangsupport.semantic

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant

/**
 * Regression for the corpus crash on `vendor/golang.org/x/text/unicode/norm`: generated tables
 * contain left-deep `"" + "..." + ...` chains thousands of operands long. Typing them top-down
 * overflowed the stack inside RecursionManager and left it inconsistent for the rest of the run.
 */
class GoDeepExpressionTest : GoSemanticTestBase() {

    override val group: String get() = "types"

    private fun deepConstFile(operands: Int): GoFile {
        val sb = StringBuilder("package deep\n\nconst packed = \"\"")
        repeat(operands) { i -> sb.append(" +\n\t\"\\x").append("%02x".format(i % 256)).append('"') }
        sb.append("\n\nvar n = len(packed)\n\nfunc f() int { return n + len(packed) }\n")
        return myFixture.addFileToProject("deep/deep.go", sb.toString()) as GoFile
    }

    fun testShortChainBaseline() {
        for (n in listOf(3, 63, 64, 65, 200)) {
            val text = "package b\n\nconst packed = \"\"" + " + \"a\"".repeat(n) + "\n"
            val file = myFixture.addFileToProject("b$n/b.go", text) as GoFile
            val value = PsiTreeUtil.findChildOfType(file, GoConstSpec::class.java)!!.expressionList.single()
            val t = semantic.typeOf(value)
            assertEquals("n=$n", GoBasicType.UNTYPED_STRING, t)
        }
    }

    fun testLongStringConcatenationTypesAndFoldsWithoutStackOverflow() {
        val file = deepConstFile(3000)
        val spec = PsiTreeUtil.findChildOfType(file, GoConstSpec::class.java)!!
        val value = spec.expressionList.single()
        assertEquals(GoBasicType.UNTYPED_STRING, semantic.typeOf(value))
        val constant = semantic.constantValue(value)
        assertTrue("expected a string constant, got $constant", constant is GoConstant.Str && constant.value.length == 3000)
        assertEquals(emptyList<Any>(), semantic.check(file))
    }

    fun testRepeatedChecksStayConsistent() {
        // The original failure poisoned RecursionManager for later files; check twice and another file after.
        val deep = deepConstFile(5000)
        repeat(2) { assertEquals(emptyList<Any>(), semantic.check(deep)) }
        val other = myFixture.addFileToProject("other/other.go", "package other\n\nfunc g() int { x := 1; return x }\n") as GoFile
        assertEquals(emptyList<Any>(), semantic.check(other))
    }
}
