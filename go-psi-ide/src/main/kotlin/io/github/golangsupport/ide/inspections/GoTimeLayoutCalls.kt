package io.github.golangsupport.ide.inspections

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner

/**
 * Which argument of a call is a time layout: `Time.Format(layout)`, `Time.AppendFormat(b, layout)`, `time.Parse(layout, value)` and
 * `time.ParseInLocation(layout, value, loc)`. The callee is resolved (a `Format` method of another type, or one that does not resolve,
 * is not one); a call whose name is none of these is rejected before any resolve, so the check is cheap on every call of a file.
 */
object GoTimeLayoutCalls {
    private val LAYOUT_INDEX = mapOf("Format" to 0, "AppendFormat" to 1, "Parse" to 0, "ParseInLocation" to 0)

    /** The layout argument of [call], or null when it is not a call of those `time` functions. */
    fun layoutArgument(call: GoCallExpr): GoExpression? {
        var callee: GoExpression? = call.expression
        while (callee is GoParenthesesExpr) callee = callee.inner as? GoExpression
        val ref = callee as? GoReferenceExpression ?: return null
        val name = ref.identifier?.text ?: return null
        val index = LAYOUT_INDEX[name] ?: return null
        val arguments = call.argumentList?.expressions ?: return null
        val argument = arguments.getOrNull(index) ?: return null
        val target = GoSemanticService.getInstance(call.project).resolve(ref).singleOrNull() ?: return null
        return if (isTimeFunction(target, name)) argument else null
    }

    private fun isTimeFunction(target: PsiElement, name: String): Boolean {
        val method = name == "Format" || name == "AppendFormat"
        when (target) {
            is GoMethodDeclaration -> if (!method || target.receiverTypeName != "Time") return false
            is GoFunctionDeclaration -> if (method) return false
            else -> return false
        }
        val file = target.containingFile as? GoFile ?: return false
        val path = GoSemanticService.getInstance(file.project).packageOf(file)?.importPath ?: file.packageName
        return path == "time"
    }
}
