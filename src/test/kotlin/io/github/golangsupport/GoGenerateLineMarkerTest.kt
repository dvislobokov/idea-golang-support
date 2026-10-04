package io.github.golangsupport

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.build.GoGenerateLineMarkerContributor

/** ▶ on each `//go:generate` line that `go generate` reads, nowhere else. */
class GoGenerateLineMarkerTest : BasePlatformTestCase() {
    fun testMarkersOnDirectivesOnly() {
        val file = myFixture.configureByText("gen.go", """
            //go:generate stringer -type=Pill
            package p

            // go:generate not a directive
            //go:generate	mockgen -source=gen.go
            func f() {
            	//go:generate indented: go generate skips it
            }
            /*
            //go:generate inside a block comment
            */
        """.trimIndent())
        val contributor = GoGenerateLineMarkerContributor()
        val marked = PsiTreeUtil.collectElements(file) { it.firstChild == null }.filter { contributor.getInfo(it) != null }.map(PsiElement::getText)
        assertEquals(listOf("//go:generate stringer -type=Pill", "//go:generate\tmockgen -source=gen.go"), marked)
        assertEquals(2, contributor.getInfo(file.findElementAt(0)!!)!!.actions.size)
    }
}
