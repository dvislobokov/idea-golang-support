package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoMulExpr
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import java.math.BigInteger

/**
 * staticcheck SA5012: an odd number of elements passed to a parameter that must hold pairs: a function that panics on
 * `len(p)%2 != 0` before anything else (directly or by passing `p` on to such a function), like `strings.NewReplacer`. The length is
 * known for variadic arguments, slice literals, `make([]T, n)` and constant slicing of those (through local variables assigned once).
 * Functions of the project are analysed from their source; of the libraries only `strings.NewReplacer` is known.
 */
class GoEvenSliceLengthRule : GoCallRule() {
    override val id: String get() = "SA5012"
    override val linter: String get() = "staticcheck"
    override val title: String get() = "Odd number of elements passed to a function that expects pairs"
    override val description: String get() =
        "Some functions take key / value pairs in a slice and panic when its length is odd (<code>strings.NewReplacer(\"a\")</code>). Flags a " +
            "slice of known odd length passed to a parameter its function checks with <code>len(p)%2 != 0</code> followed by a panic."
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES_FLOW

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val list = call.argumentList ?: return
        val args = list.expressions
        if (args.isEmpty()) return
        val ref = GoLintPsi.calleeReference(call) ?: return
        val sig = ctx.semantic.calleeSignature(call) ?: return
        val ellipsis = list.hasEllipsis
        var odd: MutableMap<Int, Pair<Int, GoExpression>>? = null
        for ((pi, param) in sig.params.withIndex()) {
            if (param.type.underlying() !is GoSliceType) continue
            val variadicSpread = sig.variadic && pi == sig.params.lastIndex && !ellipsis
            val (n, source) = if (variadicSpread) {
                if (args.size <= pi) continue
                (args.size - pi) to args[pi]
            } else {
                val arg = args.getOrNull(pi) ?: continue
                sliceLength(arg, ctx, 0) to arg
            }
            if (n > 0 && n % 2 != 0) (odd ?: HashMap<Int, Pair<Int, GoExpression>>().also { odd = it })[pi] = n to source
        }
        val found = odd ?: return
        val callee = ctx.resolve(ref).singleOrNull() as? GoFunctionOrMethodDeclaration ?: return
        val even = GoEvenParams(ctx).of(callee, 0)
        for ((pi, value) in found) {
            if (pi !in even) continue
            val (n, source) = value
            val name = sig.params[pi].name ?: continue
            val label = if (pi == sig.params.lastIndex && sig.variadic) "variadic argument" else "argument"
            ctx.report(source, "$label ${GoStaticcheckPsi.quote(name)} is expected to have even number of elements, but has $n elements")
        }
    }

    /** The length of the slice [e] evaluates to, or -1 when it is not known. */
    private fun sliceLength(e: GoExpression, ctx: GoRuleContext, depth: Int): Int {
        if (depth > 4) return -1
        val origin = GoB3Psi.origin(e, ctx)
        if (origin.resultIndex >= 0) return -1
        return when (val x = origin.expression) {
            is GoCompositeLit -> {
                if (ctx.typeOf(x).underlying() !is GoSliceType) return -1
                val elements = x.literalValue?.elements ?: return 0
                if (elements.any { it.key != null }) -1 else elements.size
            }
            is GoCallExpr -> {
                val ref = GoLintPsi.calleeReference(x) ?: return -1
                if (ref.expression != null || ref.identifier?.text != "make") return -1
                if (!GoLintPsi.isBuiltin(ctx.resolve(ref).singleOrNull() ?: return -1)) return -1
                val size = x.argumentList?.expressions?.getOrNull(0) ?: return -1
                GoStaticcheckPsi.intConstant(size, ctx)?.takeIf { it.bitLength() < 31 }?.toInt() ?: -1
            }
            is GoIndexOrSliceExpr -> {
                if (!x.isSlice) return -1
                val bounds = sliceBounds(x)
                val low = bounds[0]?.let { GoStaticcheckPsi.intConstant(it, ctx)?.takeIf { v -> v.bitLength() < 31 }?.toInt() ?: return -1 } ?: 0
                val high = bounds[1]?.let { GoStaticcheckPsi.intConstant(it, ctx)?.takeIf { v -> v.bitLength() < 31 }?.toInt() ?: return -1 } ?: run {
                    val base = x.expression ?: return -1
                    val type = ctx.typeOf(base).underlying().let { if (it is io.github.golangsupport.semantic.types.GoPointerType) it.elem.underlying() else it }
                    if (type is GoArrayType) type.length?.takeIf { it < Int.MAX_VALUE }?.toInt() ?: return -1 else sliceLength(base, ctx, depth + 1)
                }
                if (high < 0) -1 else high - low
            }
            else -> -1
        }
    }

    /** low, high, max of a slice expression (null where omitted). */
    private fun sliceBounds(x: GoIndexOrSliceExpr): Array<GoExpression?> {
        val bounds = arrayOfNulls<GoExpression>(3)
        var slot = 0
        var started = false
        var child = x.firstChild
        while (child != null) {
            when {
                child === x.lbrack -> started = true
                !started -> {}
                child.node.elementType == GoTypes.COLON -> slot++
                child is GoExpression && slot < 3 -> bounds[slot] = child
            }
            child = child.nextSibling
        }
        return bounds
    }
}

/** Which parameters of a function must hold an even number of elements (staticcheck's `evenElements` fact), by signature position. */
internal class GoEvenParams(private val ctx: GoRuleContext) {

    fun of(function: GoFunctionOrMethodDeclaration, depth: Int): Set<Int> {
        val key = if (function !is io.github.golangsupport.lang.psi.GoMethodDeclaration) GoAnalysisPsi.packagePath(function)?.let { "$it.${function.name}" } else null
        KNOWN[key]?.let { return it }
        if (depth > 3 || !GoAnalysisScope.isAnalysed(function.containingFile)) return emptySet()
        val body = function.block ?: return emptySet()
        val params = parameters(function)
        if (params.isEmpty()) return emptySet()
        val sig = ctx.semantic.declarationType(function) as? GoSignatureType ?: return emptySet()
        val result = HashSet<Int>()
        for (statement in body.statementList) {
            if (statement is GoIfStatement) evenCheck(statement, params, sig)?.let { result += it }
            if (statement !is GoIfStatement && statement !is GoForStatement && statement !is GoSwitchStatement && statement !is GoSelectStatement &&
                statement !is GoDeferStatement && statement !is GoGoStatement) {
                for (call in calls(statement)) result += forwarded(call, params, sig, depth)
            }
            if (statement is GoReturnStatement || containsReturn(statement)) break
        }
        return result
    }

    /** The parameters in signature order (unnamed ones as null). */
    private fun parameters(function: GoFunctionOrMethodDeclaration): List<GoParamDefinition?> {
        val decls = function.signature?.parameters?.parameterDeclarationList ?: return emptyList()
        return decls.flatMap { d -> d.paramDefinitionList.ifEmpty { listOf(null) } }
    }

    /** The parameter index `if len(p)%2 != 0 { …; panic(…) }` checks. */
    private fun evenCheck(statement: GoIfStatement, params: List<GoParamDefinition?>, sig: GoSignatureType): Int? {
        if (statement.initStatement != null || statement.elseStatement != null) return null
        val cond = GoLintPsi.unparen(statement.condition) as? GoConditionalExpr ?: return null
        val needle = when (cond.operator) {
            GoTypes.NEQ -> BigInteger.ZERO
            GoTypes.EQL -> BigInteger.ONE
            else -> return null
        }
        val (rem, k) = when {
            GoLintPsi.unparen(cond.left) is GoMulExpr -> cond.left to cond.right
            else -> cond.right to cond.left
        }
        val mul = GoLintPsi.unparen(rem) as? GoMulExpr ?: return null
        if (mul.rem == null || GoStaticcheckPsi.intConstant(k ?: return null, ctx) != needle) return null
        if (GoStaticcheckPsi.intConstant(mul.right ?: return null, ctx) != BigInteger.TWO) return null
        val len = GoLintPsi.unparen(mul.left) as? GoCallExpr ?: return null
        val lenRef = GoLintPsi.calleeReference(len) ?: return null
        if (lenRef.expression != null || lenRef.identifier?.text != "len" || !GoLintPsi.isBuiltin(ctx.resolve(lenRef).singleOrNull() ?: return null)) return null
        val index = paramIndex(len.argumentList?.expressions?.singleOrNull(), params, sig) ?: return null
        val then = statement.block?.statementList ?: return null
        val last = then.lastOrNull() ?: return null
        if (then.any { it is GoIfStatement || it is GoForStatement || it is GoSwitchStatement || it is GoSelectStatement || it is GoReturnStatement }) return null
        val panic = PsiTreeUtil.findChildOfType(last, GoCallExpr::class.java, false)?.takeIf { last.text.startsWith("panic(") } ?: (last as? GoCallExpr) ?: return null
        val panicRef = GoLintPsi.calleeReference(panic) ?: return null
        if (panicRef.identifier?.text != "panic" || !GoLintPsi.isBuiltin(ctx.resolve(panicRef).singleOrNull() ?: return null)) return null
        return index
    }

    /** Parameters passed unchanged to an even parameter of another function. */
    private fun forwarded(call: GoCallExpr, params: List<GoParamDefinition?>, sig: GoSignatureType, depth: Int): Set<Int> {
        val args = call.argumentList?.expressions ?: return emptySet()
        val candidates = args.withIndex().mapNotNull { (ai, a) -> paramIndex(a, params, sig)?.let { ai to it } }
        if (candidates.isEmpty()) return emptySet()
        val callee = GoLintPsi.calleeReference(call)?.let { ctx.resolve(it).singleOrNull() } as? GoFunctionOrMethodDeclaration ?: return emptySet()
        val even = of(callee, depth + 1)
        return candidates.filter { it.first in even }.map { it.second }.toSet()
    }

    /** The index of the slice-typed parameter [e] names when it is never assigned in the function; null otherwise. */
    private fun paramIndex(e: GoExpression?, params: List<GoParamDefinition?>, sig: GoSignatureType): Int? {
        val ref = GoLintPsi.unparen(e) as? GoReferenceExpression ?: return null
        if (ref.expression != null) return null
        val target = ctx.resolve(ref).singleOrNull() as? GoParamDefinition ?: return null
        val index = params.indexOf(target)
        if (index < 0 || sig.params.getOrNull(index)?.type?.underlying() !is GoSliceType) return null
        val body = PsiTreeUtil.getParentOfType(target, GoFunctionOrMethodDeclaration::class.java)?.block ?: return null
        val assigned = PsiTreeUtil.findChildrenOfType(body, GoAssignmentStatement::class.java).any { a ->
            a.leftHandExprList?.expressionList?.any { t -> (GoLintPsi.unparen(t) as? GoReferenceExpression)?.let { it.expression == null && it.identifier?.text == target.name } == true } == true
        }
        return if (assigned) null else index
    }

    private fun calls(statement: GoStatement): List<GoCallExpr> {
        val out = ArrayList<GoCallExpr>()
        fun walk(e: PsiElement) {
            var c = e.firstChild
            while (c != null) {
                if (c !is GoFunctionLit) {
                    if (c is GoCallExpr) out += c
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(statement)
        return out
    }

    private fun containsReturn(statement: GoStatement): Boolean =
        PsiTreeUtil.findChildrenOfType(statement, GoReturnStatement::class.java).any { r ->
            val literal = PsiTreeUtil.getParentOfType(r, GoFunctionLit::class.java)
            literal == null || !PsiTreeUtil.isAncestor(statement, literal, false)
        }

    private companion object {
        val KNOWN: Map<String?, Set<Int>> = mapOf("strings.NewReplacer" to setOf(0))
    }
}
