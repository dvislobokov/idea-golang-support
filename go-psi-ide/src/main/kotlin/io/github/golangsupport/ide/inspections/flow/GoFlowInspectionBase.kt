package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.flow.GoLiveness
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.scope.GoUniverse

/**
 * Base of the data-flow inspections: runs [check] once per function body (declarations and function literals, each with its own
 * [GoControlFlow]). Functions the graph does not support (syntax errors, a `goto` into a block, …) are skipped, never reported.
 */
abstract class GoFlowInspectionBase : GoAnalysisInspectionBase() {

    final override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val flow = when (element) {
            is GoFunctionOrMethodDeclaration -> GoControlFlow.of(element)
            is GoFunctionLit -> GoControlFlow.of(element)
            else -> return
        } ?: return
        check(flow, holder)
    }

    /** Reports the problems of one function body. */
    protected abstract fun check(flow: GoControlFlow, holder: ProblemsHolder)
}

/** Shared predicates of the flow inspections. */
internal object GoFlowChecks {

    fun service(element: PsiElement): GoSemanticService = GoSemanticService.getInstance(element.project)

    /** Whether [v] is declared with the predeclared `error` type. */
    fun isErrorVariable(v: GoNamedElement): Boolean = GoAnalysisPsi.isError(service(v).declarationType(v))

    /** Whether evaluating [e] calls something (conversions included) or receives from a channel. */
    fun hasSideEffects(e: GoExpression?): Boolean {
        if (e == null) return false
        if (PsiTreeUtil.findChildOfType(e, GoCallExpr::class.java, false) != null) return true
        return PsiTreeUtil.collectElements(e) { it is LeafPsiElement && it.text == "<-" }.isNotEmpty()
    }

    /** `nil`, `0`, `""`, `false`, `T{}`: the zero values people write to initialize before assigning in every branch. */
    fun isZeroLiteral(e: GoExpression?): Boolean {
        var x: PsiElement? = e
        while (x is GoParenthesesExpr) x = x.inner
        return when (x) {
            is GoLiteral -> x.text.trimStart('0', '.', '_', 'x', 'X', 'b', 'B', 'o', 'O').isEmpty() || x.text == "0.0" || x.text == "'\\x00'"
            is GoStringLiteral -> x.text == "\"\"" || x.text == "``"
            is GoCompositeLit -> x.literalValue?.elements?.isEmpty() == true
            is GoCallExpr -> isZeroConversion(x)
            is GoConversionExpr -> isZeroLiteral(PsiTreeUtil.getChildOfType(x, GoExpression::class.java))
            is GoExpression -> x.text == "false" || GoNilness.isNilLiteral(x, service(x))
            else -> false
        }
    }

    /** `rune(0)`, `int64(0)`, `T(nil)`: a conversion of a zero literal to a named or basic type. */
    private fun isZeroConversion(call: GoCallExpr): Boolean {
        val arg = call.argumentList?.expressions?.singleOrNull() ?: return false
        val callee = unparen(call.expression) as? GoReferenceExpression ?: return false
        if (!isZeroLiteral(arg)) return false
        if (callee.expression == null && callee.identifier.text in GoUniverse.BASIC_TYPES) return true
        return service(call).resolve(callee).firstOrNull() is GoTypeSpec
    }

    /**
     * The writes that replace the error value [access] stored, when no path reads that value (an error read on some path — say
     * only when `n == 0` — is the io.Reader idiom, not a lost error), counting only writes of a new result of
     * a call (`err = g()`), not resets (`err = nil`), new errors (`errors.New`, `fmt.Errorf`), values derived from it
     * (`err = fmt.Errorf("…: %w", err)` reads first), the same assignment in the next loop iteration, a re-executed declaration
     * (the variable went out of scope) or a `return f()` into a named result.
     * Empty for non-error variables, untracked ones and values that do not come from a call. Shared by [GoErrorOverwrittenInspection]
     * and [GoIneffectualAssignmentInspection] so that one assignment gets one report.
     */
    fun errorOverwrites(flow: GoControlFlow, liveness: GoLiveness, access: GoFlowAccess): List<GoFlowAccess> {
        if (!access.isWrite || access.isCompound || access.value == null) return emptyList()
        if (!flow.isTracked(access.variable) || !flow.isReachable(access.node)) return emptyList()
        if (PsiTreeUtil.findChildOfType(access.value, GoCallExpr::class.java, false) == null) return emptyList()
        // `error(nil)` is a reset written as a conversion (x/text/transform), not a result that may have failed
        if ((unparen(access.value) as? GoCallExpr)?.argumentList?.expressions?.singleOrNull()?.let { unparen(it)?.text == "nil" } == true) return emptyList()
        if (!isErrorVariable(access.variable) || liveness.isReadAfter(access)) return emptyList()
        return flow.nextAccesses(access).filter { next ->
            next !== access && next.isWrite && !next.isCompound && flow.isReachable(next.node) &&
                next.node.kind != GoFlowNode.Kind.RETURN && next.element !== next.variable &&
                (unparen(next.value) as? GoCallExpr)?.let { !isErrorConstructor(it) } == true
        }
    }

    /** `errors.New`, `errors.Join`, `fmt.Errorf`: a new error, not the result of an operation that may have failed. */
    fun isErrorConstructor(call: GoCallExpr): Boolean {
        val callee = call.expression as? GoReferenceExpression ?: return false
        val name = callee.identifier.text
        if (name != "New" && name != "Errorf" && name != "Join" || callee.expression == null) return false
        val target = service(call).resolve(callee).firstOrNull() as? GoFunctionDeclaration ?: return false
        return GoAnalysisPsi.packagePath(target).let { it == "errors" || it == "fmt" }
    }

    fun unparen(e: PsiElement?): GoExpression? {
        var x = e
        while (x is GoParenthesesExpr) x = x.inner
        return x as? GoExpression
    }

    /** The range of [element]'s whole line (with its line break) when it stands alone on it, else its own range. */
    fun lineRange(document: Document, element: PsiElement): TextRange {
        val text = document.charsSequence
        var start = element.textRange.startOffset
        var end = element.textRange.endOffset
        var lineStart = start
        while (lineStart > 0 && (text[lineStart - 1] == ' ' || text[lineStart - 1] == '\t')) lineStart--
        var lineEnd = end
        while (lineEnd < text.length && (text[lineEnd] == ' ' || text[lineEnd] == '\t' || text[lineEnd] == ';')) lineEnd++
        if ((lineStart == 0 || text[lineStart - 1] == '\n') && (lineEnd == text.length || text[lineEnd] == '\n')) {
            start = lineStart
            end = minOf(lineEnd + 1, text.length)
        }
        return TextRange(start, end)
    }
}
