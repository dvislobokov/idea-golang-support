package io.github.golangsupport.ide.duplicates

import com.intellij.dupLocator.DuplocatorSettings
import com.intellij.dupLocator.PsiElementRole
import com.intellij.dupLocator.treeHash.DuplocatorHashCallback
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoTypes

/** Locate Duplicates over the Go PSI: two functions equal but for their names are one pattern; a different function is not in it. */
class GoDuplicatesProfileTest : GoIdeTestBase() {
    private val text = """
        package p

        func sumA(xs []int) int {
        	total := 0
        	for _, x := range xs {
        		if x > 0 {
        			total += x
        		}
        	}
        	println(total)
        	return total
        }

        func sumB(values []int) int {
        	acc := 0
        	for _, v := range values {
        		if v > 0 {
        			acc += v
        		}
        	}
        	println(acc)
        	return acc
        }

        func other(s string) string {
        	return s + "!"
        }
    """.trimIndent() + "\n"

    private fun patterns(lowerBound: Int): List<List<String>> {
        val file = myFixture.configureByText("dup.go", text)
        val callback = DuplocatorHashCallback(lowerBound)
        // the dialog hashes only the languages ticked in it
        val selected = DuplocatorSettings.getInstance().SELECTED_PROFILES
        val added = selected.add(GoLanguage.displayName)
        try {
            val visitor = GoDuplicatesProfile().createVisitor(callback)
            visitor.visitNode(file)
            visitor.hashingFinished()
        } finally {
            if (added) selected.remove(GoLanguage.displayName)
        }
        val info = callback.info
        return (0 until info.patterns).map { p ->
            info.getFragmentOccurences(p).map { fragment ->
                val element = fragment.elements.first()
                PsiTreeUtil.getParentOfType(element, GoFunctionDeclaration::class.java, false)?.name ?: element.text
            }.sorted()
        }
    }

    fun testFunctionsThatDifferInNamesOnlyAreDuplicates() {
        val found = patterns(lowerBound = 10)
        assertTrue(found.toString(), found.any { it == listOf("sumA", "sumB") })
        assertFalse(found.toString(), found.any { "other" in it })
    }

    fun testTheLowerBoundIsTheTolerance() {
        assertEquals(emptyList<List<String>>(), patterns(lowerBound = 1000))
    }

    fun testIdentifiersHaveTheirRoles() {
        val file = myFixture.configureByText("roles.go", "package p\n\ntype T struct{ f int }\n\nfunc g(p int) { q := T{}; println(q.f, p) }\n")
        fun role(name: String, occurrence: Int = 0): PsiElementRole? {
            val identifiers = PsiTreeUtil.collectElements(file) { it.elementType == GoTypes.IDENTIFIER && it.text == name }
            return GoDuplicatesProfile.roleOf(identifiers[occurrence])
        }
        assertEquals(PsiElementRole.FIELD_NAME, role("f"))
        assertEquals(PsiElementRole.FUNCTION_NAME, role("g"))
        assertEquals(PsiElementRole.VARIABLE_NAME, role("p", 1))
        assertEquals(PsiElementRole.FUNCTION_NAME, role("println"))
        assertEquals(PsiElementRole.FIELD_NAME, role("f", 1))
        assertTrue(GoDuplicatesProfile().isMyLanguage(GoLanguage))
        assertNull(GoDuplicatesProfile.roleOf(file.firstChild as PsiElement))
    }
}
