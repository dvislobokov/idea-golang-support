package io.github.golangsupport.ide.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoTypeSwitchGuard
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.resolve.GoFieldKeyReference

/** Read/write classification of Go value references. */
object GoAccess {

    /**
     * The access of the expression [expr]: `x = ...`, `x, y := ...` (existing `x`), `for x = range`,
     * `case x = <-ch` are writes; `x += 1` and `x++` are read-writes; everything else (including
     * `&x` and `x.f = 1` for `x`) is a read. Struct literal keys write the field.
     */
    @JvmStatic
    fun accessOf(expr: PsiElement): ReadWriteAccessDetector.Access {
        if (isFieldKey(expr)) return ReadWriteAccessDetector.Access.Write
        var e: PsiElement = expr
        while (e.parent is GoParenthesesExpr) e = e.parent
        val list = e.parent as? GoLeftHandExprList ?: return ReadWriteAccessDetector.Access.Read
        return when (val statement = list.parent) {
            is GoAssignmentStatement ->
                if (statement.assignOp.text == "=") ReadWriteAccessDetector.Access.Write else ReadWriteAccessDetector.Access.ReadWrite
            is GoIncDecStatement -> ReadWriteAccessDetector.Access.ReadWrite
            is GoRangeClause, is GoRecvStatement -> ReadWriteAccessDetector.Access.Write
            else -> ReadWriteAccessDetector.Access.Read
        }
    }

    /** `Name` in `T{Name: v}`: the struct field key (not a map key expression). */
    @JvmStatic
    fun isFieldKey(expr: PsiElement): Boolean =
        expr is GoReferenceExpression && expr.parent is GoKey && expr.reference is GoFieldKeyReference

    @JvmStatic
    fun isWrite(expr: PsiElement): Boolean = accessOf(expr) != ReadWriteAccessDetector.Access.Read
}

/** Read/write highlighting of usages (Highlight Usages, Find Usages grouping) for variables, fields and parameters. */
class GoReadWriteAccessDetector : ReadWriteAccessDetector() {

    override fun isReadWriteAccessible(element: PsiElement): Boolean =
        (element is GoVarDefinition || element is GoConstDefinition || element is GoParamDefinition ||
            element is GoReceiver || element is GoFieldDefinition || element is GoAnonymousFieldDefinition) &&
            GoIdeFeatureGate.enabled(GoIdeFeature.USAGES, element.project)

    /** Declarations that assign a value: initialized vars, `:=`, range/recv/type-switch bindings, consts. */
    override fun isDeclarationWriteAccess(element: PsiElement): Boolean = when (element) {
        is GoConstDefinition -> true
        is GoVarDefinition -> when (val parent = element.parent) {
            is GoVarSpec -> parent.expressionList.isNotEmpty()
            is GoShortVarDeclaration, is GoRangeClause, is GoRecvStatement, is GoTypeSwitchGuard -> true
            else -> false
        }
        else -> false
    }

    override fun getReferenceAccess(referencedElement: PsiElement, reference: PsiReference): Access =
        getExpressionAccess(reference.element)

    override fun getExpressionAccess(expression: PsiElement): Access = when (expression) {
        is GoExpression -> GoAccess.accessOf(expression)
        else -> Access.Read
    }
}
