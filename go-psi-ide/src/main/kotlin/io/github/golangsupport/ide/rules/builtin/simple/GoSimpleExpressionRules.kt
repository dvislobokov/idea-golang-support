package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import java.math.BigInteger

/**
 * staticcheck S1009: `x != nil && len(x) != 0` (and `> 0`, `== n`, `>= n` with a constant `n`; `x == nil || len(x) == 0` and the like)
 * of a slice, map or channel: `len` of a nil one is zero, the nil check is redundant.
 */
class GoNilLenCheckRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1009"
    override val title: String get() = "Omit redundant nil check on slices, maps, and channels"
    override val description: String get() =
        "The <code>len</code> function is defined for all slices, maps, and channels, even nil ones, which have a length of zero. It is not " +
            "necessary to check for nil before checking that their length is not zero."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val kind = match(expression, ctx) ?: return
        ctx.report(expression, "should omit nil check; len() for $kind is defined as zero", *GoRewriteFix.offer("Remove nil check", expression, ctx, ::fix))
    }

    /** `nil slices` / `nil maps` / `nil channels`, or null. */
    private fun match(e: PsiElement, ctx: GoRuleContext): String? {
        if (e !is GoAndExpr && e !is GoOrExpr) return null
        val eqNil = e is GoOrExpr
        val check = (e as GoBinaryExpr).left as? GoConditionalExpr ?: return null
        val length = e.right as? GoConditionalExpr ?: return null
        if (if (eqNil) check.eql == null else check.neq == null) return null
        val x = check.left
        val call = length.left as? GoCallExpr ?: return null
        val k = length.right ?: return null
        val rhsOp = GoExpressionPsi.op(length) ?: return null
        if (!GoExpressionPsi.isNil(check.right, ctx) || check.right !is GoReferenceExpression) return null
        if (!GoSimplePsi.isBuiltinCall(call, "len", ctx)) return null
        val arg = GoStaticcheckPsi.arguments(call)?.singleOrNull() ?: return null
        if (!sideEffectFree(x) || !GoSimplePsi.sameNonDynamic(x, arg, ctx)) return null
        val zero = constZero(k, ctx) ?: return null
        val ok = if (eqNil) {
            when (rhsOp) {
                "==" -> zero
                "<=" -> true
                "<" -> !zero
                else -> false
            }
        } else {
            when (rhsOp) {
                "==", ">=" -> !zero
                "!=" -> zero
                ">" -> true
                else -> false
            }
        }
        if (!ok) return null
        val type = ctx.typeOf(x)
        if (type is GoTypeParamType) return null
        return when (type.underlying()) {
            is GoSliceType -> "nil slices"
            is GoMapType -> "nil maps"
            is GoChanType -> "nil channels"
            else -> null
        }
    }

    /** staticcheck's `MayHaveSideEffects` without purity facts: no calls (not even `len`), no receives. */
    private fun sideEffectFree(x: GoExpression): Boolean = GoSimplePsi.isPure(x) && PsiTreeUtil.findChildOfType(x, GoCallExpr::class.java) == null && x !is GoCallExpr

    /** A basic literal or a constant name: whether it is the integer zero; null for anything else (`-1`, `pkg.N`, `n + 1`). */
    private fun constZero(k: GoExpression, ctx: GoRuleContext): Boolean? {
        if (k is GoLiteral) return GoExpressionPsi.isIntLiteral(k, BigInteger.ZERO)
        if (k !is GoReferenceExpression || k.expression != null) return null
        if (ctx.resolve(k).singleOrNull() !is GoConstDefinition) return null
        return (ctx.semantic.constantValue(k) as? GoConstant.Int)?.value == BigInteger.ZERO
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        match(element, ctx) ?: return null
        val length = (element as GoBinaryExpr).right ?: return null
        if (GoSimplePsi.commentsOutside(element, listOf(length.textRange))) return null
        return GoSimplePsi.replaceWith(element, length.text)
    }
}

/** staticcheck S1010: `s[a:len(s)]` is `s[a:]`. */
class GoSliceLenRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1010"
    override val title: String get() = "Omit default slice index"
    override val description: String get() =
        "When slicing, the second index defaults to the length of the value, making <code>s[n:len(s)]</code> and <code>s[n:]</code> equivalent."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val high = match(expression, ctx) ?: return
        ctx.report(high, "should omit second index in slice, s[a:len(s)] is identical to s[a:]",
            *GoRewriteFix.offer("Simplify slice expression", high, ctx) { e, c -> fix(e, c) })
    }

    private fun match(e: PsiElement, ctx: GoRuleContext): GoCallExpr? {
        if (e !is GoIndexOrSliceExpr || !e.isSlice) return null
        val slice = GoSimplePsi.slice(e) ?: return null
        val high = slice.high as? GoCallExpr ?: return null
        if (!GoSimplePsi.isIdent(slice.operand)) return null
        val arg = GoStaticcheckPsi.arguments(high)?.singleOrNull() ?: return null
        if (!GoSimplePsi.isIdent(arg) || GoSimplePsi.identName(arg) != GoSimplePsi.identName(slice.operand)) return null
        if (!GoSimplePsi.isBuiltinCall(high, "len", ctx)) return null
        val target = GoSimplePsi.target(slice.operand, ctx) ?: return null
        return high.takeIf { GoSimplePsi.target(arg, ctx) == target }
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val slice = element.parent as? GoIndexOrSliceExpr ?: return null
        if (match(slice, ctx) !== element || GoSimplePsi.hasComments(element)) return null
        return GoSimplePsi.replaceWith(element, "")
    }
}

/** staticcheck S1019: `make(chan T, 0)` is `make(chan T)`; `make([]T, n, n)` is `make([]T, n)`. */
class GoMakeLenCapRule : GoSimpleCallRule() {
    override val id: String get() = "S1019"
    override val title: String get() = "Simplify \"make\" call by omitting redundant arguments"
    override val description: String get() =
        "The <code>make</code> function has default values for the length and capacity arguments. For channels, the length defaults to zero, " +
            "and for slices, the capacity defaults to the length."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        if (GoSimplePsi.calleeName(call) != "make") return
        val args = GoSimplePsi.args(call)
        if (args.size !in 2..3 || call.argumentList?.hasEllipsis != false) return
        if (!GoSimplePsi.isBuiltin(call.expression, "make", ctx)) return
        val type = GoExpressionPsi.render(args[0])
        if (args.size == 2) {
            if (!GoExpressionPsi.isIntLiteral(args[1] as? GoExpression, BigInteger.ZERO)) return
            val made = ctx.typeOf(call).let { if (it is GoTypeParamType) it.coreType ?: return else it }
            if (made.underlying() !is GoChanType) return
            ctx.report(args[1], "should use make($type) instead", *GoRewriteFix.offer("Remove redundant size argument", args[1], ctx) { e, c -> fix(e, c, 2) })
        } else {
            val size = args[1] as? GoExpression ?: return
            val capacity = args[2] as? GoExpression ?: return
            if (!GoExpressionPsi.sameCode(size, capacity)) return
            ctx.report(size, "should use make($type, ${GoExpressionPsi.render(size)}) instead",
                *GoRewriteFix.offer("Remove redundant capacity argument", size, ctx) { e, c -> fix(e, c, 3) })
        }
    }

    /** Deletes the last argument of the `make` call [element] is the second argument of; [count]: the arguments it must have. */
    private fun fix(element: PsiElement, ctx: GoRuleContext, count: Int): List<GoEditPlan.Edit>? {
        val call = PsiTreeUtil.getParentOfType(element, GoCallExpr::class.java) ?: return null
        val args = GoSimplePsi.args(call)
        if (args.size != count || args[1] !== element || !GoSimplePsi.isBuiltin(call.expression, "make", ctx)) return null
        val previous = args[count - 2]
        val last = args[count - 1]
        // the dropped capacity is evaluated no more: it must have no effects
        if (count == 3 && !GoSimplePsi.isPure(last)) return null
        val range = TextRange(previous.textRange.endOffset, last.textRange.endOffset)
        if (GoSimplePsi.hasCommentsBetween(previous, last) || GoSimplePsi.hasComments(last)) return null
        return listOf(GoEditPlan.Edit(range.startOffset, range.endOffset, ""))
    }
}

/**
 * staticcheck S1020: `if _, ok := i.(T); ok && i != nil` - when `ok` is true, `i` can't be nil; likewise an `if i != nil` whose only
 * statement is `if _, ok := i.(T); ok { ... }`.
 */
class GoAssertNotNilRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1020"
    override val title: String get() = "Omit redundant nil check in type assertion"
    override val description: String get() = "<code>if _, ok := i.(T); ok &amp;&amp; i != nil {}</code> is <code>if _, ok := i.(T); ok {}</code>."

    /** [statement] is reported; [nested]: the outer `if i != nil` goes, else the condition becomes [ok]. */
    private class Match(val statement: GoIfStatement, val ok: GoExpression, val okName: String, val name: String, val nested: Boolean)

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val m = match(expression, ctx) ?: return
        ctx.report(m.statement, GoSimplePsi.keywordRange(m.statement), "when ${m.okName} is true, ${m.name} can't be nil",
            *GoRewriteFix.offer("Remove nil check", m.statement, ctx, ::fix))
    }

    private fun match(e: PsiElement, ctx: GoRuleContext): Match? {
        val statement = e.parent as? GoIfStatement ?: return null
        if (statement.condition !== e) return null
        return when (e) {
            is GoAndExpr -> {
                val (assert, ok) = assertion(statement, ctx) ?: return null
                val right = e.right ?: return null
                val okOperand = when {
                    isNotNil(right, assert, ctx) -> e.left
                    isNotNil(e.left, assert, ctx) -> right
                    else -> return null
                }
                if (!GoSimplePsi.isIdent(okOperand) || GoSimplePsi.target(okOperand, ctx) != ok) return null
                Match(statement, okOperand, ok.name ?: return null, (assert as? GoNamedElement)?.name ?: return null, false)
            }
            is GoConditionalExpr -> {
                if (statement.initStatement != null || statement.elseStatement != null) return null
                val lhs = e.left
                val target = GoSimplePsi.target(lhs, ctx) ?: return null
                if (!isNotNil(e, target, ctx)) return null
                val inner = statement.block?.statementList?.singleOrNull() as? GoIfStatement ?: return null
                val innerElse = inner.elseStatement
                if (innerElse != null && (innerElse.statement as? GoBlock)?.statementList?.isEmpty() != true) return null
                val (assert, ok) = assertion(inner, ctx) ?: return null
                if (assert != target) return null
                val cond = inner.condition ?: return null
                if (GoSimplePsi.target(cond, ctx) != ok) return null
                Match(inner, cond, ok.name ?: return null, (target as? GoNamedElement)?.name ?: return null, true)
            }
            else -> null
        }
    }

    /** `x != nil` with `x` naming [target]. */
    private fun isNotNil(e: GoExpression, target: PsiElement, ctx: GoRuleContext): Boolean {
        if (e !is GoConditionalExpr || e.neq == null) return false
        if (!GoSimplePsi.isIdent(e.left) || GoSimplePsi.target(e.left, ctx) != target) return false
        return e.right is GoReferenceExpression && GoExpressionPsi.isNil(e.right, ctx)
    }

    /** `_, ok := x.(T)` / `_, ok = x.(T)` as the init statement of [statement]: (what `x` names, what `ok` names). */
    private fun assertion(statement: GoIfStatement, ctx: GoRuleContext): Pair<PsiElement, GoNamedElement>? {
        val init = statement.initStatement ?: return null
        val (lhs, rhs) = when (init) {
            is GoShortVarDeclaration -> init.varDefinitionList to init.expressionList
            is GoAssignmentStatement -> if (init.assignOp.assign == null) return null else (init.leftHandExprList?.expressionList ?: return null) to init.expressionList
            else -> return null
        }
        if (lhs.size != 2 || !GoSimplePsi.isBlank(lhs[0]) || GoSimplePsi.isBlank(lhs[1])) return null
        val assertion = rhs.singleOrNull() as? GoTypeAssertionExpr ?: return null
        val x = assertion.expression
        if (!GoSimplePsi.isIdent(x)) return null
        val assert = GoSimplePsi.target(x, ctx) ?: return null
        val ok = when (val o = lhs[1]) {
            is GoVarDefinition -> o
            is GoReferenceExpression -> if (o.expression == null) GoSimplePsi.target(o, ctx) as? GoNamedElement else null
            else -> null
        } ?: return null
        return assert to ok
    }

    /** [element] is the reported `if`: its own condition (`ok && x != nil`), or the `if x != nil` around it. */
    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val statement = element as? GoIfStatement ?: return null
        val own = statement.condition?.let { match(it, ctx) }
        if (own != null && !own.nested && own.statement === statement) {
            val cond = statement.condition ?: return null
            if (GoSimplePsi.commentsOutside(cond, listOf(own.ok.textRange))) return null
            return GoSimplePsi.replaceWith(cond, own.ok.text)
        }
        val outer = statement.parent?.parent as? GoIfStatement ?: return null
        val m = outer.condition?.let { match(it, ctx) } ?: return null
        if (!m.nested || m.statement !== statement) return null
        if (statement.elseStatement != null || GoSimplePsi.commentsOutside(outer, listOf(statement.textRange))) return null
        return GoSimplePsi.replace(outer, GoSimplePsi.reindented(statement, -1))
    }
}

/**
 * staticcheck S1030: `string(buf.Bytes())` is `buf.String()` and `[]byte(buf.String())` is `buf.Bytes()` for a `bytes.Buffer` (not as a
 * map index: `m[string(buf.Bytes())]` is optimized by the compiler).
 */
class GoBufferConversionRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1030"
    override val title: String get() = "Use bytes.Buffer.String or bytes.Buffer.Bytes"
    override val description: String get() =
        "<code>bytes.Buffer</code> has both a <code>String</code> and a <code>Bytes</code> method. It is almost never necessary to use " +
            "<code>string(buf.Bytes())</code> or <code>[]byte(buf.String())</code> - simply use the other method. The only exception to this are map " +
            "lookups. Due to a compiler optimization, <code>m[string(buf.Bytes())]</code> is more efficient than <code>m[buf.String()]</code>."

    /** The receiver and the method to call instead. */
    private class Match(val receiver: GoExpression, val method: String)

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val m = match(expression, ctx) ?: return
        ctx.report(expression, "should use ${GoExpressionPsi.render(m.receiver)}.${m.method}() instead of ${GoExpressionPsi.render(expression)}",
            *GoRewriteFix.offer("Simplify conversion", expression, ctx, ::fix))
    }

    private fun match(e: PsiElement, ctx: GoRuleContext): Match? {
        val arg = when (e) {
            is GoCallExpr -> GoSimplePsi.args(e).singleOrNull()
            is GoConversionExpr -> PsiTreeUtil.getChildOfType(e, GoExpression::class.java)
            else -> null
        } as? GoCallExpr ?: return null
        val name = GoSimplePsi.calleeName(arg) ?: return null
        if (name != "Bytes" && name != "String" || GoSimplePsi.args(arg).isNotEmpty()) return null
        val receiver = (arg.expression as? GoReferenceExpression)?.expression ?: return null
        if (e is GoCallExpr && GoExpressionPsi.builtinName(e.expression, ctx) != "string" && !GoExpressionPsi.isConversion(e, ctx)) return null
        if (GoSimplePsi.callee(arg, METHODS, ctx) != "bytes.Buffer.$name") return null
        if (GoLintPsi.packagePath(ctx.file) == "bytes") return null
        val type = ctx.typeOf(e as GoExpression)
        if (name == "Bytes") {
            if (!(type is GoBasicType && type.kind == io.github.golangsupport.semantic.types.GoBasicKind.STRING)) return null
            val parent = e.parent
            if (parent is GoIndexOrSliceExpr && !parent.isSlice) return null
            return Match(receiver, "String")
        }
        val elem = (type as? GoSliceType)?.elem as? GoBasicType ?: return null
        if (elem.name != "byte") return null
        return Match(receiver, "Bytes")
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val m = match(element, ctx) ?: return null
        if (GoSimplePsi.commentsOutside(element, listOf(m.receiver.textRange))) return null
        return GoSimplePsi.replaceWith(element, "${m.receiver.text}.${m.method}()")
    }

    private companion object {
        val METHODS = setOf("Bytes", "String")
    }
}

/**
 * staticcheck S1032: `sort.Sort(sort.IntSlice(x))` is `sort.Ints(x)` (`Float64Slice` / `Float64s`, `StringSlice` / `Strings`), unless the
 * enclosing function also sorts something else with `sort.Sort`.
 */
class GoSortHelperRule : GoSimpleCallRule() {
    override val id: String get() = "S1032"
    override val title: String get() = "Use sort.Ints(x), sort.Float64s(x), and sort.Strings(x)"
    override val description: String get() =
        "The <code>sort.Ints</code>, <code>sort.Float64s</code> and <code>sort.Strings</code> functions are easier to read than " +
            "<code>sort.Sort(sort.IntSlice(x))</code>, <code>sort.Sort(sort.Float64Slice(x))</code> and <code>sort.Sort(sort.StringSlice(x))</code>."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val (slice, helper) = match(call, ctx) ?: return
        val owner = GoPsiUtil.functionOwner(call) ?: return
        val permissible = PsiTreeUtil.findChildrenOfType(owner, GoCallExpr::class.java).any { other ->
            GoSimplePsi.calleeName(other) == "Sort" && other !== call && GoSimplePsi.callee(other, SORT, ctx) == "sort.Sort" && match(other, ctx) == null
        }
        if (permissible) return
        ctx.report(call, "should use sort.$helper(...) instead of sort.Sort(sort.$slice(...))", *GoRewriteFix.offer("Use sort.$helper", call, ctx, ::fix))
    }

    /** (`IntSlice`, `Ints`) for `sort.Sort(sort.IntSlice(x))`. */
    private fun match(call: GoCallExpr, ctx: GoRuleContext): Pair<String, String>? {
        if (GoSimplePsi.calleeName(call) != "Sort") return null
        val conversion = GoStaticcheckPsi.arguments(call)?.singleOrNull() as? GoCallExpr ?: return null
        val ref = conversion.expression as? GoReferenceExpression ?: return null
        if (ref.expression == null) return null
        val slice = ref.identifier.text
        val helper = HELPERS[slice] ?: return null
        if (GoSimplePsi.callee(call, SORT, ctx) != "sort.Sort") return null
        if (GoStaticcheckPsi.referenceKey(ref, ctx) != "sort.$slice") return null
        return slice to helper
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val (_, helper) = match(call, ctx) ?: return null
        val x = GoStaticcheckPsi.arguments(GoStaticcheckPsi.arguments(call)?.single() as GoCallExpr)?.singleOrNull() ?: return null
        if (GoSimplePsi.commentsOutside(call, listOf(x.textRange))) return null
        val qualifier = GoSimplePsi.qualifier(call) ?: return null
        return GoSimplePsi.replaceWith(call, "$qualifier$helper(${x.text})")
    }

    private companion object {
        val SORT = setOf("Sort")
        val HELPERS = mapOf("IntSlice" to "Ints", "Float64Slice" to "Float64s", "StringSlice" to "Strings")
    }
}

/** staticcheck S1040: `x.(I)` where `x` already has the interface type `I` can only fail when `x` is nil. */
class GoSameTypeAssertionRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1040"
    override val title: String get() = "Type assertion to current type"
    override val description: String get() =
        "The type assertion <code>x.(SomeInterface)</code>, when <code>x</code> already has type <code>SomeInterface</code>, can only fail if " +
            "<code>x</code> is nil. Usually, this is left-over code from when <code>x</code> had a different type and you can safely delete the type " +
            "assertion. If you want to check that <code>x</code> is not nil, consider being explicit and using an actual <code>if x == nil</code> " +
            "comparison instead of relying on the type assertion panicking."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (!matches(expression, ctx)) return
        expression as GoTypeAssertionExpr
        ctx.report(expression, "type assertion to the same type: ${GoExpressionPsi.render(expression.expression)} already has type ${GoExpressionPsi.render(expression.type ?: return)}",
            *GoRewriteFix.offer("Remove type assertion", expression, ctx, ::fix))
    }

    private fun matches(e: PsiElement, ctx: GoRuleContext): Boolean {
        if (e !is GoTypeAssertionExpr) return false
        val typeNode = e.type ?: return false
        val t1 = GoExpressionPsi.typeOf(typeNode)
        if (t1 is GoTypeParamType || t1.underlying() !is GoInterfaceType || !GoTypePredicates.isKnown(t1)) return false
        val t2 = ctx.typeOf(e.expression)
        return GoTypePredicates.isKnown(t2) && GoTypePredicates.identical(t1, t2)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val assertion = element as? GoTypeAssertionExpr ?: return null
        if (!matches(assertion, ctx) || isCommaOk(assertion)) return null
        if (GoSimplePsi.commentsOutside(assertion, listOf(assertion.expression.textRange))) return null
        return GoSimplePsi.replaceWith(assertion, assertion.expression.text)
    }

    /** `v, ok := x.(I)` (also `=` and `var`): the second result is used, the assertion cannot just go. */
    private fun isCommaOk(e: GoTypeAssertionExpr): Boolean = when (val p = e.parent) {
        is GoShortVarDeclaration -> p.varDefinitionList.size == 2 && p.expressionList.size == 1
        is GoAssignmentStatement -> p.leftHandExprList?.expressionList?.size == 2 && p.expressionList.size == 1
        is GoVarSpec -> p.varDefinitionList.size == 2 && p.expressionList.size == 1
        else -> false
    }
}
