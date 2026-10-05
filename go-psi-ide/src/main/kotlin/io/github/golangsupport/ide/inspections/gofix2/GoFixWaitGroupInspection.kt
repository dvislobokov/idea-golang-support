package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block

/**
 * modernize `waitgroup` (go1.25): `wg.Add(1)` right before `go func() { defer wg.Done(); … }()` (or `wg.Done()` as the last
 * statement of the literal) → `wg.Go(func() { … })`. Only a literal without parameters and arguments, on a `sync.WaitGroup`.
 */
class GoFixWaitGroupInspection : GoFix2InspectionBase() {
    override val minVersion = "1.25"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoGoStatement || !GoFixPsi.isStatementList(element.parent)) return null
        val call = element.expression as? GoCallExpr ?: return null
        val literal = call.expression as? GoFunctionLit ?: return null
        val arguments = call.argumentList ?: return null
        if (GoFixPsi.args(call).isNotEmpty() || literal.signature?.parameters?.parameterDeclarationList?.isNotEmpty() != false || literal.signature?.result != null) return null
        val body = literal.block ?: return null
        val add = (GoFixPsi.previousStatement(element) as? GoSimpleStatement)?.let { s -> s.leftHandExprList?.expressionList?.singleOrNull() as? GoCallExpr } ?: return null
        val group = receiverText(add, "Add") ?: return null
        if (GoFixPsi.args(add).singleOrNull()?.text != "1") return null
        val statements = body.statementList
        val done = (statements.firstOrNull() as? GoDeferStatement)?.takeIf { d -> (d.expression as? GoCallExpr)?.let { receiverText(it, "Done") } == group }
            ?: (statements.lastOrNull() as? GoSimpleStatement)?.takeIf { s -> (s.leftHandExprList?.expressionList?.singleOrNull() as? GoCallExpr)?.let { c -> GoEditText.expressionStatement(c) != null && receiverText(c, "Done") == group } == true }
            ?: return null
        if (!GoFixPsi.isCallTo(add, "sync.WaitGroup.Add")) return null
        val doneCall = (done as? GoDeferStatement)?.expression as? GoCallExpr ?: (done as GoSimpleStatement).leftHandExprList!!.expressionList.single() as GoCallExpr
        if (!GoFixPsi.isCallTo(doneCall, "sync.WaitGroup.Done")) return null
        val keyword = TextRange(0, element.go.textLength)
        return GoFixFinding(element, "Goroutine creation can be simplified using WaitGroup.Go", listOf("Simplify by using WaitGroup.Go" to { _ ->
            listOf(
                GoFixPsi.deleteStatement(add.parent.parent),
                GoFixEdit(TextRange(element.textRange.startOffset, literal.textRange.startOffset), "$group.Go("),
                GoFixPsi.deleteStatement(done),
                GoFixEdit.replace(arguments, ")"),
            )
        }), keyword)
    }

    /** `wg` of `wg.Add(…)` / `wg.Done()` (the receiver as written) when the method is [method]. */
    private fun receiverText(call: GoCallExpr, method: String): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier.text != method) return null
        return callee.expression?.text
    }
}
