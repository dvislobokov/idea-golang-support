package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Highlighting and quick-fix helpers of the GoLand-parity inspection tests (PLAN.md G7). */
abstract class GoParityInspectionTestBase : GoSemanticIdeTestBase() {

    protected fun doHighlight(text: String, vararg tools: LocalInspectionTool, fileName: String = "a.go") {
        myFixture.enableInspections(*tools)
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    /** Applies the quick fix named [fix] at `<caret>` and compares with [after]. */
    protected fun doFix(before: String, fix: String, after: String, vararg tools: LocalInspectionTool, fileName: String = "a.go") {
        myFixture.enableInspections(*tools)
        myFixture.configureByText(fileName, before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.firstOrNull { it.text == fix } ?: error("'$fix' not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    /** The intention texts offered at `<caret>`. */
    protected fun offered(text: String, vararg tools: LocalInspectionTool): List<String> {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        return myFixture.availableIntentions.map { it.text }
    }
}
