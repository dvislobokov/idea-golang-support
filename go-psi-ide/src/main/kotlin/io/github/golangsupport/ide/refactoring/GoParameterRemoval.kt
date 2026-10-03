package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoParameters
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier

/**
 * Removing a parameter of a function or method: its call sites (direct calls and method expressions `T.M(recv, …)`), the arguments
 * bound to it, and the text ranges that drop it from the signature and from each call. Shared by Safe Delete of a parameter and the
 * "Remove unused parameter" fix of the unused-parameter inspection.
 */
internal object GoParameterRemoval {

    /** A call of the function: [call] whose callee is the reference; [methodExpression] for `T.M(recv, …)` (arguments shifted by one). */
    class CallSite(val call: GoCallExpr, val methodExpression: Boolean)

    /** The references to a function: [calls] and the places that use it as a value ([values]: passed, assigned, compared). */
    class References(val calls: List<CallSite>, val values: List<PsiElement>)

    /** The function or method declaration, or the interface method spec, whose parameter (not result) [def] is; null for a function literal or type. */
    fun ownerOf(def: GoParamDefinition): PsiElement? {
        val signature = (def.parent as? GoParameterDeclaration)?.parent?.let { it as? GoParameters }?.parent as? GoSignature ?: return null
        if (signature.parameters !== def.parent.parent) return null
        return signature.parent.takeIf { it is GoFunctionDeclaration || it is GoMethodDeclaration || it is GoMethodSpec }
    }

    /**
     * Every reference to [decl] (a function or method declaration, or an interface method spec) in [scope] (comments and other languages
     * skipped); with [stopAtValue] the search ends at the first value use.
     */
    fun references(decl: PsiElement, scope: SearchScope, stopAtValue: Boolean): References {
        val calls = ArrayList<CallSite>()
        val values = ArrayList<PsiElement>()
        ReferencesSearch.search(decl, scope).forEach(Processor { reference ->
            ProgressManager.checkCanceled()
            val element = reference.element
            if (!isCodeReference(element)) return@Processor true
            val site = callSiteOf(element, decl)
            if (site == null) {
                values += element
                return@Processor !stopAtValue
            }
            calls += site
            true
        })
        return References(calls, values)
    }

    /** A reference in Go code: not in a comment nor in another language. */
    fun isCodeReference(element: PsiElement): Boolean = element.containingFile is GoFile && PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) == null

    /** The call [element] (a reference to [decl]) is the callee of, or null when it uses [decl] as a value. */
    fun callSiteOf(element: PsiElement, decl: PsiElement): CallSite? {
        val call = (element as? GoReferenceExpression)?.let(::callOf) ?: return null
        return CallSite(call, (decl is GoMethodDeclaration || decl is GoMethodSpec) && isTypeOperand(element.qualifier))
    }

    /** Every reference to [decl] in [scope] as a call, or null when one of them uses the function as a value. */
    fun callSites(decl: GoFunctionOrMethodDeclaration, scope: SearchScope): List<CallSite>? =
        references(decl, scope, stopAtValue = true).takeIf { it.values.isEmpty() }?.calls

    /** The call whose callee is [ref] (through parentheses and an instantiation `f[int]`). */
    private fun callOf(ref: GoReferenceExpression): GoCallExpr? {
        var callee: PsiElement = ref
        while (true) {
            val parent = callee.parent
            callee = when {
                parent is GoParenthesesExpr -> parent
                parent is GoIndexOrSliceExpr && parent.firstChild === callee -> parent
                else -> break
            }
        }
        val call = callee.parent as? GoCallExpr ?: return null
        return call.takeIf { it.expression === callee }
    }

    /** Whether [qualifier] names a type (`T.M`, `(*T).M`): a method expression. */
    private fun isTypeOperand(qualifier: PsiElement?): Boolean {
        var e = qualifier ?: return false
        while (true) {
            e = when (e) {
                is GoParenthesesExpr -> e.inner ?: return false
                is GoUnaryExpr -> e.expression ?: return false
                else -> break
            }
        }
        if (e is GoType) return true
        val ref = e as? GoReferenceExpression ?: return false
        return GoSemanticService.getInstance(ref.project).resolve(ref).any { it is GoTypeSpec }
    }

    fun declarations(signature: GoSignature?): List<GoParameterDeclaration> = signature?.parameters?.parameterDeclarationList.orEmpty()

    fun definitions(signature: GoSignature?): List<GoParamDefinition> = declarations(signature).flatMap { it.paramDefinitionList }

    /** Argument slot of [def] (a declaration without names takes one slot). */
    fun slot(signature: GoSignature?, def: GoParamDefinition): Int {
        var index = 0
        for (pd in declarations(signature)) {
            val defs = pd.paramDefinitionList
            if (defs.isEmpty()) {
                index++
                continue
            }
            val i = defs.indexOf(def)
            if (i >= 0) return index + i
            index += defs.size
        }
        return -1
    }

    fun arity(signature: GoSignature?): Int = declarations(signature).sumOf { maxOf(1, it.paramDefinitionList.size) }

    fun isVariadic(signature: GoSignature?): Boolean = declarations(signature).lastOrNull()?.isVariadic == true

    /**
     * The arguments of [site] that bind to [slot] (all the trailing ones for a variadic slot, none when the call passes nothing there),
     * or null when they cannot be told apart: `f(g())` with a multi-value `g` gives one argument for several parameters.
     */
    fun argumentsAt(site: CallSite, slot: Int, arity: Int, variadic: Boolean): List<PsiElement>? {
        val all = site.call.arguments
        val shift = if (site.methodExpression) 1 else 0 // `T.M(recv, a, b)`: the receiver comes first
        if (all.size < shift) return null
        val args = all.subList(shift, all.size)
        val spread = site.call.argumentList?.node?.findChildByType(GoTypes.ELLIPSIS) != null
        val fits = if (variadic && !spread) args.size >= arity - 1 else args.size == arity
        if (!fits || slot < 0) return null
        return if (variadic && slot == arity - 1) args.subList(slot, args.size) else listOf(args[slot])
    }

    /** No calls (conversions included, conservatively) and no receives: dropping the argument changes nothing. */
    fun isPure(e: PsiElement): Boolean = PsiTreeUtil.findChildOfType(e, GoCallExpr::class.java, false) == null &&
        PsiTreeUtil.findChildrenOfType(e, GoUnaryExpr::class.java).none { it.operator === GoTypes.ARROW } && !(e is GoUnaryExpr && e.operator === GoTypes.ARROW)

    /** The range that drops [def] from [signature]: its name of `a, b T`, or its whole declaration with a comma. */
    fun signatureRange(signature: GoSignature, def: GoParamDefinition): TextRange? {
        val parameters = signature.parameters
        val pd = def.parent as? GoParameterDeclaration ?: return null
        val names = pd.paramDefinitionList
        if (names.size > 1) {
            val i = names.indexOf(def)
            return listRemoval(names, i, i + 1, 0, 0, null)
        }
        val decls = declarations(signature)
        val i = decls.indexOf(pd)
        if (i < 0) return null
        return listRemoval(decls, i, i + 1, parenOpen(parameters) ?: return null, parenClose(parameters) ?: return null, null)
    }

    /** The range that drops [removed] (consecutive arguments of [site]) from the call, with a trailing `...` when the last ones go. */
    fun argumentRange(site: CallSite, removed: List<PsiElement>): TextRange? {
        val list = site.call.argumentList ?: return null
        val args = site.call.arguments
        val from = args.indexOf(removed.first())
        if (from < 0) return null
        val ellipsis = list.node.findChildByType(GoTypes.ELLIPSIS)?.textRange?.endOffset
        return listRemoval(args, from, from + removed.size, parenOpen(list) ?: return null, parenClose(list) ?: return null, ellipsis)
    }

    /** The deletions that remove [def] from the signature and its argument from every call, by file; null when one call cannot be rewritten. */
    fun removal(decl: GoFunctionOrMethodDeclaration, def: GoParamDefinition, calls: List<CallSite>): Map<PsiFile, List<TextRange>>? {
        val signature = decl.signature ?: return null
        val slot = slot(signature, def)
        val arity = arity(signature)
        val variadic = isVariadic(signature)
        val result = LinkedHashMap<PsiFile, MutableList<TextRange>>()
        result.getOrPut(decl.containingFile) { ArrayList() } += signatureRange(signature, def) ?: return null
        for (site in calls) {
            val removed = argumentsAt(site, slot, arity, variadic) ?: return null
            if (removed.isEmpty()) continue // `f(a)` for `f(a int, xs ...int)`: nothing bound to the variadic slot
            result.getOrPut(site.call.containingFile) { ArrayList() } += argumentRange(site, removed) ?: return null
        }
        return result
    }

    private fun parenOpen(e: PsiElement): Int? = e.node.findChildByType(GoTypes.LPAREN)?.textRange?.endOffset

    private fun parenClose(e: PsiElement): Int? = e.node.getChildren(null).lastOrNull { it.elementType == GoTypes.RPAREN }?.startOffset

    /** The range that removes `items[from until to]` with their separating commas; everything between the parentheses when all go. */
    private fun listRemoval(items: List<PsiElement>, from: Int, to: Int, open: Int, close: Int, tailEnd: Int?): TextRange = when {
        from == 0 && to == items.size -> TextRange(open, close)
        to < items.size -> TextRange(items[from].textRange.startOffset, items[to].textRange.startOffset)
        else -> TextRange(items[from - 1].textRange.endOffset, maxOf(tailEnd ?: 0, items.last().textRange.endOffset))
    }
}
