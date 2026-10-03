package io.github.golangsupport.ide.injection

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner

/**
 * Where a string literal is the argument of a known package-level function: the shared half of the injectors. A cheap name check
 * ([calleeName], the identifier right of the dot) runs before resolve, so that the many unrelated literals of a file cost nothing.
 */
object GoInjectionTargets {
    /** `pkg.Name`, `pkg` being the import path of the package that declares the function. */
    data class Callee(val pkg: String, val name: String)

    /** The literal's content range inside the host (without the quotes), through the host's own escaper. */
    fun contentRange(host: PsiLanguageInjectionHost): TextRange = host.createLiteralTextEscaper().relevantTextRange

    /** [e] without the parentheses around it, and the outermost parenthesis wrapping it. */
    private fun outer(e: PsiElement): PsiElement {
        var r = e
        while (r.parent is GoParenthesesExpr) r = r.parent
        return r
    }

    /** The call whose argument number [index] is [e] (looking through parentheses), or null. */
    fun callOfArgument(e: PsiElement, index: Int): GoCallExpr? {
        val arg = outer(e)
        val list = arg.parent as? GoArgumentList ?: return null
        val call = list.parent as? GoCallExpr ?: return null
        return call.takeIf { list.expressions.getOrNull(index) === arg }
    }

    /** The conversion `T(e)` whose operand is [e] (looking through parentheses), or null. */
    fun conversionOf(e: PsiElement): GoConversionExpr? = outer(e).parent as? GoConversionExpr

    fun calleeReference(call: GoCallExpr): GoReferenceExpression? {
        var e: GoExpression? = call.expression
        while (e is GoParenthesesExpr) e = e.inner as? GoExpression
        return e as? GoReferenceExpression
    }

    /** The unresolved name of the called function (`MustCompile` of `regexp.MustCompile`), or null for a callee that is not a name. */
    fun calleeName(call: GoCallExpr): String? = calleeReference(call)?.identifier?.text

    /** The function a call invokes, when it is a package-level function (not a method) of a package that resolves. */
    fun calleeOf(call: GoCallExpr): Callee? {
        val ref = calleeReference(call) ?: return null
        val service = GoSemanticService.getInstance(call.project)
        val target = service.resolve(ref).singleOrNull() as? GoFunctionDeclaration ?: return null
        val file = target.containingFile as? GoFile ?: return null
        val pkg = service.packageOf(file)?.importPath ?: file.packageName ?: return null
        return Callee(pkg, target.name ?: return null)
    }
}
