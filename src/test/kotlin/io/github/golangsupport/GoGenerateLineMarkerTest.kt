package io.github.golangsupport

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.build.GoGenerateLineMarkerProvider

/** ▶ on each `//go:generate` line that `go generate` reads, nowhere else; GoLand's tooltip and no popup actions (they would join Alt+Enter). */
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
        val provider = GoGenerateLineMarkerProvider()
        val marked = PsiTreeUtil.collectElements(file) { it.firstChild == null }.filter { provider.getLineMarkerInfo(it) != null }.map(PsiElement::getText)
        assertEquals(listOf("//go:generate stringer -type=Pill", "//go:generate\tmockgen -source=gen.go"), marked)
        val info = provider.getLineMarkerInfo(file.findElementAt(0)!!)!!
        assertEquals("Run go generate on comment", info.lineMarkerTooltip)
        assertNull(info.createGutterRenderer().popupMenuActions)
    }
}
