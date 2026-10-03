package io.github.golangsupport.ide.rules.builtin.statements

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.simple.GoRewriteFix
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * staticcheck SA6001: `k := string(b)` (`b` a byte slice) used only as the key of map reads `m[k]`: written as `m[string(b)]` the
 * compiler avoids the copy. Every use must be a plain `m[k]` read in the same function; the variable must not be written.
 */
class GoMapByteKeyRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA6001"
    override val title: String get() = "Missing an optimization opportunity when indexing maps by byte slices"
    override val description: String get() =
        "Map keys must be comparable, which precludes the use of byte slices. This usually leads to using string keys and converting byte " +
            "slices to strings. Normally, a conversion of a byte slice to a string needs to copy the data and causes allocations. The compiler, " +
            "however, recognizes <code>m[string(b)]</code> and uses the data of <code>b</code> directly, without copying it, because it knows " +
            "that the data can't change during the map lookup."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        val pairs: List<Pair<GoVarDefinition, GoExpression>> = when (val s = GoStatementsPsi.unwrap(statement)) {
            is GoShortVarDeclaration -> s.varDefinitionList.zip(s.expressionList).takeIf { s.varDefinitionList.size == s.expressionList.size }
            is GoVarDeclaration -> s.varSpecList.flatMap { spec -> pairs(spec) }
            else -> null
        } ?: return
        val scope = GoStatementsPsi.list(statement) ?: return
        for ((def, value) in pairs) {
            if (!isBytesToString(value, ctx)) continue
            val refs = GoStatementsPsi.references(scope, def, ctx)
            if (refs.isEmpty() || !refs.all { isMapKeyRead(it, scope, ctx) }) continue
            ctx.report(value, "m[string(key)] would be more efficient than k := string(key); m[k]")
        }
    }

    private fun pairs(spec: GoVarSpec): List<Pair<GoVarDefinition, GoExpression>> =
        if (spec.type == null && spec.varDefinitionList.size == spec.expressionList.size) spec.varDefinitionList.zip(spec.expressionList) else emptyList()

    /** `string(b)` converting to the predeclared `string` from a type whose type set has a byte slice. */
    private fun isBytesToString(value: GoExpression, ctx: GoRuleContext): Boolean {
        val call = GoLintPsi.unparen(value) as? GoCallExpr ?: return false
        val callee = GoLintPsi.unparen(call.expression)
        if (GoExpressionPsi.builtinName(callee, ctx) != "string") return false
        val arg = GoStaticcheckPsi.arguments(call)?.singleOrNull() ?: return false
        val set = GoExpressionPsi.typeSet(ctx.typeOf(arg)) ?: return false
        return set.any { t -> ((t.underlying() as? GoSliceType)?.elem?.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8 }
    }

    /** `m[k]` reading map `m`, [ref] being the whole index, outside function literals and not assigned to. */
    private fun isMapKeyRead(ref: GoReferenceExpression, scope: PsiElement, ctx: GoRuleContext): Boolean {
        if (GoStatementsPsi.insideLiteral(ref, scope)) return false
        val index = ref.parent as? GoIndexOrSliceExpr ?: return false
        val (operand, key) = GoSimplePsi.index(index) ?: return false
        if (key !== ref) return false
        if (ctx.typeOf(operand).underlying() !is GoMapType) return false
        var top: PsiElement = index
        while (top.parent is GoParenthesesExpr) top = top.parent
        val lhs = top.parent as? GoLeftHandExprList ?: return true
        return when (val owner = lhs.parent) {
            is GoAssignmentStatement, is GoIncDecStatement, is GoRangeClause -> false
            is GoRecvStatement -> owner.leftHandExprList !== lhs
            else -> true
        }
    }
}

/**
 * staticcheck SA6003: `for _, r := range []rune(s)` converts the string only to range over it; ranging over `s` yields the same runes
 * without the allocation. Also `rs := []rune(s)` used only by such a loop. The inline form is S1029's: SA6003 stays quiet on it while
 * S1029 runs.
 */
class GoRangeRunesRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA6003"
    override val title: String get() = "Converting a string to a slice of runes before ranging over it"
    override val description: String get() =
        "You may want to loop over the runes in a string. Instead of converting the string to a slice of runes and looping over that, you " +
            "can loop over the string itself. Besides being easier to read, it will also avoid unnecessary memory allocations."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement) return
        val range = statement.rangeClause ?: return
        val vars = range.leftHandExprList?.expressionList ?: range.varDefinitionList
        if (vars.size != 2 || !GoSimplePsi.isBlank(vars[0]) || GoSimplePsi.isBlank(vars[1])) return
        val x = GoLintPsi.unparen(range.expression) ?: return
        if (conversion(x, ctx) != null) {
            if (GoStaticcheckPsi.enabled(S1029, ctx)) return
            ctx.report(statement, GoStatementsPsi.keywordRange(statement), MESSAGE, *GoRewriteFix.offer("Range over the string", statement, ctx, ::fix))
            return
        }
        if (!isOnlyRangedVariable(x, ctx)) return
        ctx.report(statement, GoStatementsPsi.keywordRange(statement), MESSAGE)
    }

    /** `[]rune(s)` (or a conversion to a named slice of `rune`) of a string `s`: the operand. */
    private fun conversion(x: GoExpression, ctx: GoRuleContext): GoExpression? {
        val operand = when (x) {
            is GoConversionExpr -> GoPsiUtil.children(x, GoExpression::class.java).singleOrNull()
            is GoCallExpr -> GoStaticcheckPsi.arguments(x)?.singleOrNull()?.takeIf {
                val callee = GoLintPsi.unparen(x.expression) as? GoReferenceExpression
                callee != null && ctx.resolve(callee).singleOrNull() is GoTypeSpec
            }
            else -> null
        } ?: return null
        val slice = ctx.typeOf(x).underlying() as? GoSliceType ?: return null
        if ((slice.elem as? GoBasicType)?.kind != GoBasicKind.INT32) return null
        if ((ctx.typeOf(operand).underlying() as? GoBasicType)?.kind?.isString != true) return null
        return operand
    }

    /** `rs` defined as `rs := []rune(s)` in this function and used nowhere but in this range clause. */
    private fun isOnlyRangedVariable(x: GoExpression, ctx: GoRuleContext): Boolean {
        val ref = x as? GoReferenceExpression ?: return false
        if (ref.expression != null) return false
        val def = ctx.resolve(ref).singleOrNull() as? GoVarDefinition ?: return false
        val value = initializer(def) ?: return false
        if (conversion(GoLintPsi.unparen(value) ?: return false, ctx) == null) return false
        val owner = GoPsiUtil.functionOwner(def) ?: return false
        if (GoPsiUtil.functionOwner(ref) !== owner) return false
        return GoStatementsPsi.references(owner, def, ctx).singleOrNull() === ref
    }

    private fun initializer(def: GoVarDefinition): GoExpression? = when (val p = def.parent) {
        is GoShortVarDeclaration -> p.varDefinitionList.indexOf(def).let { i -> p.expressionList.takeIf { it.size == p.varDefinitionList.size }?.getOrNull(i) }
        is GoVarSpec -> p.varDefinitionList.indexOf(def).let { i -> p.expressionList.takeIf { it.size == p.varDefinitionList.size }?.getOrNull(i) }
        else -> null
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val loop = element as? GoForStatement ?: return null
        val range = loop.rangeClause?.expression ?: return null
        val x = GoLintPsi.unparen(range) ?: return null
        val operand = conversion(x, ctx) ?: return null
        if (GoSimplePsi.hasComments(x)) return null
        return listOf(GoEditPlan.Edit(range.textRange.startOffset, range.textRange.endOffset, operand.text))
    }

    private companion object {
        const val S1029 = "S1029"
        const val MESSAGE = "should range over string, not []rune(string)"
    }
}

/** staticcheck SA9010: `defer setup()` where `setup()` returns the cleanup function: the cleanup never runs. The fix calls it. */
class GoDeferredFuncNotCalledRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA9010"
    override val title: String get() = "Returned function should be called in defer"
    override val description: String get() =
        "If you have a function such as <code>func f() func()</code>, then <code>defer f()</code> calls <code>f</code> when the surrounding " +
            "function returns and drops the function it returns; <code>defer f()()</code> was probably meant."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoDeferStatement) return
        val call = statement.expression as? GoCallExpr ?: return
        if (GoExpressionPsi.isConversion(call, ctx)) return
        val sig = ctx.typeOf(call).underlying() as? GoSignatureType ?: return
        if (!GoTypePredicates.isKnown(sig)) return
        val fixes = if (sig.params.isEmpty()) GoRewriteFix.offer("Call the returned function", statement, ctx, ::fix) else emptyArray()
        ctx.report(statement, "deferred return function not called", *fixes)
    }

    private fun fix(element: PsiElement, @Suppress("UNUSED_PARAMETER") ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = (element as? GoDeferStatement)?.expression as? GoCallExpr ?: return null
        return listOf(GoEditPlan.Edit(call.textRange.endOffset, call.textRange.endOffset, "()"))
    }
}

/**
 * govet `defers`: `defer log.Println(time.Since(start))` evaluates `time.Since` when the defer statement runs, not when the function
 * returns. Function literals in the deferred call are not looked into. The fix wraps the deferred call in a function literal.
 */
class GoVetDefersRule : GoVetStatementRule() {
    override val id: String get() = "govet:defers"
    override val title: String get() = "time.Since evaluated at the defer statement"
    override val description: String get() =
        "govet <code>defers</code>: the arguments of a deferred call are evaluated immediately, so <code>defer log.Println(time.Since(start))</code> " +
            "logs a duration close to zero. Use <code>defer func() { log.Println(time.Since(start)) }()</code>."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoDeferStatement) return
        val deferred = statement.expression ?: return
        if (!deferred.text.contains("Since")) return
        val calls = ArrayList<GoCallExpr>()
        collect(deferred, calls)
        for (call in calls) {
            val ref = GoLintPsi.calleeReference(call) ?: continue
            if (ref.identifier.text != "Since" || GoStaticcheckPsi.calleeKey(ref, ctx) != "time.Since") continue
            ctx.report(call, "call to time.Since is not deferred", *GoRewriteFix.offer("Wrap the deferred call in a function literal", call, ctx, ::fix))
        }
    }

    private fun collect(e: PsiElement, out: MutableList<GoCallExpr>) {
        if (e is GoFunctionLit) return
        if (e is GoCallExpr) out += e
        var child = e.firstChild
        while (child != null) {
            collect(child, out)
            child = child.nextSibling
        }
    }

    private fun fix(element: PsiElement, @Suppress("UNUSED_PARAMETER") ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val defer = PsiTreeUtil.getParentOfType(element, GoDeferStatement::class.java) ?: return null
        val call = defer.expression as? GoCallExpr ?: return null
        if (PsiTreeUtil.getParentOfType(element, GoFunctionLit::class.java, true, GoDeferStatement::class.java) != null) return null
        if (call.text.contains('\n') || GoSimplePsi.hasComments(call)) return null
        return listOf(GoEditPlan.Edit(call.textRange.startOffset, call.textRange.endOffset, "func() { ${call.text} }()"))
    }
}
