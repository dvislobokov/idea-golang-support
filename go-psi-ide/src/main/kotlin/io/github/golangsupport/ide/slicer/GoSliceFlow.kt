package io.github.golangsupport.ide.slicer

import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.usages.GoAccess
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner

/** One node of a data-flow slice: [terminal] when nothing flows further (a zero value, a range variable). */
data class GoSliceStep(val element: PsiElement, val terminal: Boolean = false)

/**
 * One level of the data flow of Go values for Analyze | Data Flow to / from Here. Inside a function the reaching definitions of
 * go-psi-semantic narrow a read to the writes that may have produced its value; across functions a parameter is fed by the arguments
 * of its calls and a call by the `return` statements of its function.
 */
object GoSliceFlow {
    /** The variable or parameter the caret is on (its definition, or a reference to it); null elsewhere. */
    fun target(atCaret: PsiElement): PsiElement? {
        var e: PsiElement? = atCaret
        while (e != null && e !is com.intellij.psi.PsiFile) {
            when (e) {
                is GoVarDefinition, is GoParamDefinition -> return e
                is GoReferenceExpression -> return e.takeIf { variableOf(it) != null }
                is GoExpression -> return null
            }
            e = e.parent
        }
        return null
    }

    /** "Data Flow to Here": the expressions and declarations whose values [element] may hold. */
    fun sources(element: PsiElement, scope: SearchScope): List<GoSliceStep> = when (val e = unparen(element)) {
        is GoReferenceExpression -> referenceSources(e, scope)
        is GoVarDefinition -> (listOfNotNull(valueAssignedTo(e)) + writesOf(e, scope).mapNotNull(::valueAssignedTo)).map(::GoSliceStep)
        is GoParamDefinition -> argumentsFor(e, scope).map(::GoSliceStep)
        is GoCallExpr -> returnedBy(e).map(::GoSliceStep)
        else -> emptyList()
    }

    /** "Data Flow from Here": where the value of [element] goes. */
    fun consumers(element: PsiElement, scope: SearchScope): List<GoSliceStep> = when (val e = unparen(element)) {
        is GoVarDefinition, is GoParamDefinition -> readsOf(e as io.github.golangsupport.lang.psi.GoNamedElement, scope).map(::GoSliceStep)
        is GoReferenceExpression -> if (GoAccess.isWrite(e)) readsReachedBy(e, scope).map(::GoSliceStep) else destinations(e, scope).map(::GoSliceStep)
        is GoExpression -> destinations(e, scope).map(::GoSliceStep)
        else -> emptyList()
    }

    private fun unparen(e: PsiElement?): PsiElement? {
        var x = e
        while (x is GoParenthesesExpr) x = x.inner
        return x
    }

    /** The variable or parameter [ref] names, when it is a plain identifier. */
    private fun variableOf(ref: GoReferenceExpression): io.github.golangsupport.lang.psi.GoNamedElement? {
        if (ref.expression != null) return null
        val target = ref.reference?.resolve()
        return (target as? GoVarDefinition) ?: (target as? GoParamDefinition)
    }

    private fun referenceSources(ref: GoReferenceExpression, scope: SearchScope): List<GoSliceStep> {
        val variable = variableOf(ref) ?: return emptyList()
        if (GoAccess.isWrite(ref)) return listOfNotNull(valueAssignedTo(ref)).map(::GoSliceStep)
        val flow = GoControlFlow.enclosing(ref)
        val read = flow?.accessesAt(ref)?.firstOrNull { !it.isWrite }
        val reaching = flow?.let { GoReachingDefinitions.of(it) }
        if (flow == null || read == null || reaching == null || !flow.isTracked(read.variable)) return sources(variable, scope)
        return reaching.definitionsOf(read).map { def ->
            val value = def.value
            when {
                value != null -> GoSliceStep(value)
                def.element is GoParamDefinition -> GoSliceStep(def.element)
                // a zero value, a range or receive variable: the definition itself, the end of this branch; `x += 1` goes on to its operand
                else -> GoSliceStep(def.element, terminal = !def.isCompound)
            }
        }.distinct()
    }

    /** The expression stored into [target] (a left-hand side of `=` or a variable of `:=` / `var`): matched by position. */
    fun valueAssignedTo(target: PsiElement): GoExpression? {
        val (targets, values) = when (val parent = target.parent) {
            is GoLeftHandExprList -> (parent.parent as? GoAssignmentStatement)?.let { parent.expressionList to it.expressionList } ?: return null
            is GoShortVarDeclaration -> parent.varDefinitionList to parent.expressionList
            is GoVarSpec -> parent.varDefinitionList to parent.expressionList
            else -> return null
        }
        val index = targets.indexOf(target)
        return when {
            index < 0 || values.isEmpty() -> null
            values.size == targets.size -> values[index]
            values.size == 1 -> values[0]
            else -> null
        }
    }

    private fun writesOf(variable: io.github.golangsupport.lang.psi.GoNamedElement, scope: SearchScope): List<GoReferenceExpression> =
        ReferencesSearch.search(variable, scope).findAll().mapNotNull { it.element as? GoReferenceExpression }.filter { GoAccess.isWrite(it) }.sortedBy { it.textOffset }

    private fun readsOf(variable: io.github.golangsupport.lang.psi.GoNamedElement, scope: SearchScope): List<GoReferenceExpression> =
        ReferencesSearch.search(variable, scope).findAll().mapNotNull { it.element as? GoReferenceExpression }.filter { !GoAccess.isWrite(it) }.sortedBy { it.textOffset }

    /** The reads that may observe the value written at [write]; every read of the variable when the function is not analysed. */
    private fun readsReachedBy(write: GoReferenceExpression, scope: SearchScope): List<PsiElement> {
        val variable = variableOf(write) ?: return emptyList()
        val flow = GoControlFlow.enclosing(write)
        val access = flow?.accessesAt(write)?.firstOrNull { it.isWrite }
        val reaching = flow?.let { GoReachingDefinitions.of(it) }
        if (flow == null || access == null || reaching == null || !flow.isTracked(access.variable)) return readsOf(variable, scope)
        return flow.accesses.filter { !it.isWrite && it.variable == access.variable && access in reaching.definitionsOf(it) }.map { it.element }.distinct()
    }

    /** Where the value of [expression] is stored or passed: the variable it initializes, the parameter it is an argument of, the calls it is returned to. */
    private fun destinations(expression: GoExpression, scope: SearchScope): List<PsiElement> {
        var e: PsiElement = expression
        while (e.parent is GoParenthesesExpr) e = e.parent
        return when (val parent = e.parent) {
            is GoAssignmentStatement -> {
                val targets = parent.leftHandExprList.expressionList
                val values = parent.expressionList
                val index = values.indexOf(e)
                listOfNotNull(if (values.size == targets.size) targets.getOrNull(index) else null)
            }
            is GoShortVarDeclaration -> listOfNotNull(parent.varDefinitionList.getOrNull(parent.expressionList.indexOf(e)).takeIf { parent.expressionList.size == parent.varDefinitionList.size })
            is GoVarSpec -> listOfNotNull(parent.varDefinitionList.getOrNull(parent.expressionList.indexOf(e)).takeIf { parent.expressionList.size == parent.varDefinitionList.size })
            is GoArgumentList -> {
                val call = parent.parent as? GoCallExpr ?: return emptyList()
                val index = PsiTreeUtil.getChildrenOfTypeAsList(parent, GoExpression::class.java).indexOf(e)
                val parameters = parametersOf(calleeOf(call) ?: return emptyList())
                listOfNotNull(parameters.getOrNull(index) ?: parameters.lastOrNull()?.takeIf { isVariadic(it) })
            }
            is GoReturnStatement -> (GoPsiUtil.functionOwner(parent) as? GoFunctionOrMethodDeclaration)?.let { callsOf(it, scope) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun calleeOf(call: GoCallExpr): GoFunctionOrMethodDeclaration? = (unparen(call.expression) as? GoReferenceExpression)?.reference?.resolve() as? GoFunctionOrMethodDeclaration

    private fun parametersOf(function: GoFunctionOrMethodDeclaration): List<GoParamDefinition> =
        function.signature?.parameters?.parameterDeclarationList?.flatMap { it.paramDefinitionList }.orEmpty()

    private fun isVariadic(parameter: GoParamDefinition): Boolean = (parameter.parent as? GoParameterDeclaration)?.text?.contains("...") == true

    /** The calls of [function] in [scope]: its references that are callees. */
    private fun callsOf(function: GoFunctionOrMethodDeclaration, scope: SearchScope): List<GoCallExpr> =
        ReferencesSearch.search(function, scope).findAll().mapNotNull { reference ->
            var callee: PsiElement = reference.element
            while (callee.parent is GoParenthesesExpr) callee = callee.parent
            (callee.parent as? GoCallExpr)?.takeIf { unparen(it.expression) == reference.element }
        }.sortedWith(compareBy({ it.containingFile.name }, { it.textOffset }))

    /** The arguments the calls of the function of [parameter] pass for it (all the trailing ones for a variadic parameter). */
    private fun argumentsFor(parameter: GoParamDefinition, scope: SearchScope): List<GoExpression> {
        val signature = PsiTreeUtil.getParentOfType(parameter, GoSignature::class.java) ?: return emptyList()
        val function = signature.parent as? GoFunctionOrMethodDeclaration ?: return emptyList()
        val index = parametersOf(function).indexOf(parameter).takeIf { it >= 0 } ?: return emptyList()
        val variadic = isVariadic(parameter)
        return callsOf(function, scope).flatMap { call ->
            val arguments = call.argumentList?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, GoExpression::class.java) }.orEmpty()
            if (variadic) arguments.drop(index) else listOfNotNull(arguments.getOrNull(index))
        }
    }

    /** The values the function called by [call] returns (the first result of each `return`; a bare `return` gives nothing). */
    private fun returnedBy(call: GoCallExpr): List<GoExpression> {
        val function = calleeOf(call) ?: return emptyList()
        val body = function.block ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(body, GoReturnStatement::class.java)
            .filter { GoPsiUtil.functionOwner(it) == function }
            .mapNotNull { it.expressionList.firstOrNull() }
    }
}
