package io.github.golangsupport.ide.hierarchy

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * Callers and callees of Go functions for the call hierarchy.
 *
 * Callers come from [ReferencesSearch] (the word index restricted by the use scope), grouped by the enclosing
 * function or method declaration: a call inside a function literal belongs to the declaration around the literal, a
 * call in a package-level `var` initializer to that variable. Every reference counts, not only `f(...)`: a function
 * passed as a value (`http.HandleFunc("/", h)`) is a call site the reader wants to see. For a method, the callers of
 * the interface methods it implements ([GoImplementations.superMethods]) are added with "via Iface": such a call may
 * reach another implementation, as calls through a super method do in the Java hierarchy.
 *
 * Callees are the calls in the body (function literals included) whose callee expression resolves, through
 * [GoSemanticService.resolve], to a function, a method or an interface method; conversions, builtins and calls of
 * function-typed variables are left out.
 */
object GoCalls {

    /** A caller (or callee) declaration with its call sites and, for calls through an interface, that interface's name. */
    data class Site(val element: PsiElement, val usages: List<PsiElement>, val via: String?)

    /** Whether the call hierarchy can start at (or expand) [element]. */
    fun isCallable(element: PsiElement?): Boolean = element is GoFunctionOrMethodDeclaration || element is GoMethodSpec

    fun callers(target: PsiElement, scope: SearchScope): List<Site> {
        val sites = LinkedHashMap<PsiElement, MutableList<PsiElement>>()
        val direct = HashSet<PsiElement>()
        val via = HashMap<PsiElement, String>()
        collectCallers(target, scope, sites) { direct += it }
        if (target is GoMethodDeclaration) {
            // Interfaces anywhere (io.Writer for a Write method); their callers only within the hierarchy's scope.
            for (spec in GoImplementations.superMethods(target, GlobalSearchScope.allScope(target.project))) {
                val name = GoImplementations.interfaceSpecOf(spec)?.name ?: continue
                collectCallers(spec, scope, sites) { via.putIfAbsent(it, name) }
            }
        }
        return sites.map { (owner, usages) -> Site(owner, usages, if (owner in direct) null else via[owner]) }
    }

    private fun collectCallers(target: PsiElement, scope: SearchScope, sites: MutableMap<PsiElement, MutableList<PsiElement>>, found: (PsiElement) -> Unit) {
        ReferencesSearch.search(target, scope).forEach(Processor { reference ->
            ProgressManager.checkCanceled()
            val element = reference.element
            if (element.containingFile !is GoFile || PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) return@Processor true
            val owner = callerOf(element) ?: return@Processor true
            val usages = sites.getOrPut(owner) { ArrayList() }
            if (element !in usages) usages += element
            found(owner)
            true
        })
    }

    /** The declaration a call site belongs to: the function or method around it, or the first variable of a package-level `var` spec. */
    fun callerOf(element: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(element, GoFunctionOrMethodDeclaration::class.java)?.let { return it }
        val spec = PsiTreeUtil.getParentOfType(element, GoVarSpec::class.java) ?: return null
        return PsiTreeUtil.findChildOfType(spec, GoVarDefinition::class.java)
    }

    fun callees(declaration: PsiElement): List<Site> {
        val body = (declaration as? GoFunctionOrMethodDeclaration)?.block ?: return emptyList()
        val semantic = GoSemanticService.getInstance(declaration.project)
        val sites = LinkedHashMap<PsiElement, MutableList<PsiElement>>()
        for (call in PsiTreeUtil.findChildrenOfType(body, GoCallExpr::class.java)) {
            ProgressManager.checkCanceled()
            val reference = calleeReference(call.expression) ?: continue
            val target = semantic.resolve(reference).firstOrNull(::isCallable) ?: continue
            if ((target.containingFile as? GoFile)?.packageName == "builtin") continue
            sites.getOrPut(target) { ArrayList() } += call
        }
        return sites.map { (target, calls) -> Site(target, calls, null) }
    }

    /** `f`, `pkg.F`, `x.M` as written, also through parentheses and an explicit instantiation (`f[int](x)`). */
    private fun calleeReference(expression: PsiElement?): GoReferenceExpression? = when (expression) {
        is GoReferenceExpression -> expression
        is GoParenthesesExpr -> calleeReference(PsiTreeUtil.getChildOfType(expression, io.github.golangsupport.lang.psi.GoExpression::class.java))
        is GoIndexOrSliceExpr -> calleeReference(expression.firstChild)
        else -> null
    }
}
