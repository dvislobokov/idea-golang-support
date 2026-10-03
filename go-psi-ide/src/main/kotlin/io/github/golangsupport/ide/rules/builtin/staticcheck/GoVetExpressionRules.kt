package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType

/** govet `nilfunc`: `f == nil` for a function or method `f` is always false (`!=`: true). */
class GoVetNilFuncRule : GoVetExpressionRule() {
    override val id: String get() = "govet:nilfunc"
    override val title: String get() = "Comparison of a function with nil"
    override val description: String get() =
        "govet <code>nilfunc</code>: a declared function or method is never nil, so <code>f == nil</code> is always false. A call " +
            "<code>f() == nil</code> or a variable of function type was probably meant."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoConditionalExpr) return
        val op = GoExpressionPsi.op(expression)
        if (op != "==" && op != "!=") return
        val right = expression.right ?: return
        val left = expression.left
        val other = when {
            GoExpressionPsi.isNil(left, ctx) -> right
            GoExpressionPsi.isNil(right, ctx) -> left
            else -> return
        }
        val ref = GoLintPsi.unparen(other) as? GoReferenceExpression ?: return
        val target = ctx.resolve(ref).singleOrNull() ?: return
        if (target !is GoFunctionDeclaration && target !is GoMethodDeclaration && target !is GoMethodSpec) return
        if (GoLintPsi.isBuiltin(target)) return
        ctx.report(expression, "comparison of function ${ref.identifier.text} $op nil is always ${op == "!="}")
    }
}

/** govet `bools`: `e || e` (redundant) and `x != c1 || x != c2` / `x == c1 && x == c2` (suspect) among side-effect-free operands. */
class GoVetBoolsRule : GoVetExpressionRule() {
    override val id: String get() = "govet:bools"
    override val title: String get() = "Redundant or suspect boolean expression"
    override val description: String get() =
        "govet <code>bools</code>: <code>a == b || a == b</code> repeats an operand; <code>x != 1 || x != 2</code> is always true and " +
            "<code>x == 1 &amp;&amp; x == 2</code> always false. Operands with calls split the chain (their order matters)."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val name: String
        val tok: String
        val badEq: String
        when (expression) {
            is GoOrExpr -> { name = "or"; tok = "||"; badEq = "!=" }
            is GoAndExpr -> { name = "and"; tok = "&&"; badEq = "==" }
            else -> return
        }
        if (isNested(expression)) return // handled with the outermost expression of the chain
        val exprs = ArrayList<GoExpression>()
        split(expression, expression.javaClass, exprs)
        var i = 0
        for (j in 0..exprs.size) {
            if (j == exprs.size || !GoExpressionPsi.noEffects(exprs[j], ctx)) {
                if (i < j) {
                    val set = exprs.subList(i, j)
                    checkRedundant(set, name, tok, ctx)
                    checkSuspect(set, name, tok, badEq, ctx)
                }
                i = j + 1
            }
        }
    }

    private fun checkRedundant(exprs: List<GoExpression>, name: String, tok: String, ctx: GoRuleContext) {
        val seen = HashMap<List<String>, GoExpression>()
        for (e in exprs) {
            val first = seen.putIfAbsent(GoExpressionPsi.tokens(e), e) ?: continue
            if (twinReports(first, e, ctx)) continue
            val text = GoExpressionPsi.render(e)
            ctx.report(e, "redundant $name: $text $tok $text")
        }
    }

    private fun checkSuspect(exprs: List<GoExpression>, name: String, tok: String, badEq: String, ctx: GoRuleContext) {
        val seen = HashMap<List<String>, GoExpression>()
        for (e in exprs) {
            if (e !is GoConditionalExpr || GoExpressionPsi.op(e) != badEq) continue
            val right = e.right ?: continue
            val x = when {
                ctx.semantic.constantValue(right) != null -> e.left
                ctx.semantic.constantValue(e.left) != null -> right
                else -> continue
            }
            val prev = seen.putIfAbsent(GoExpressionPsi.tokens(x), e) ?: continue
            if (GoExpressionPsi.tokens(prev) == GoExpressionPsi.tokens(e)) continue // redundant, reported above
            ctx.report(e, "suspect $name: ${GoExpressionPsi.render(e)} $tok ${GoExpressionPsi.render(prev)}")
        }
    }

    /** SA4000 reports `e || e` when the two are the operands of one expression: vet stays quiet then. */
    private fun twinReports(a: GoExpression, b: GoExpression, ctx: GoRuleContext): Boolean {
        val parent = a.parent as? GoBinaryExpr ?: return false
        if (b.parent !== parent || parent.left !== b && parent.right !== b) return false
        return GoStaticcheckPsi.enabled(GoIdenticalOperandsRule.ID, ctx)
    }

    /** vet's `boolOp.split`, right to left: the operands joined by the same operator, through parentheses. */
    private fun split(e: GoExpression, kind: Class<out GoExpression>, out: MutableList<GoExpression>) {
        var cur: GoExpression? = e
        while (true) {
            val u = GoLintPsi.unparen(cur) ?: return
            if (kind.isInstance(u)) {
                val b = u as GoBinaryExpr
                b.right?.let { split(it, kind, out) }
                cur = b.left
            } else {
                out += u
                return
            }
        }
    }

    private fun isNested(e: GoExpression): Boolean {
        var p = e.parent
        while (p is GoParenthesesExpr) p = p.parent
        return p != null && p.javaClass == e.javaClass
    }
}

/** govet `stringintconv`: `string(i)` of an integer (not `byte` / `rune`) yields one rune, not digits. Fixes: `fmt.Sprint(i)`, `string(rune(i))`. */
class GoVetStringIntConvRule : GoVetExpressionRule() {
    override val id: String get() = "govet:stringintconv"
    override val title: String get() = "Conversion of an integer to a string"
    override val description: String get() =
        "govet <code>stringintconv</code>: <code>string(65)</code> is <code>\"A\"</code>, not <code>\"65\"</code>. Format with " +
            "<code>fmt.Sprint</code> / <code>strconv.Itoa</code>, or write <code>string(rune(i))</code> when a rune is meant."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoCallExpr) return
        val ref = GoLintPsi.calleeReference(expression) ?: return
        if (ref !== expression.expression) return
        val arg = GoStaticcheckPsi.arguments(expression)?.singleOrNull() ?: return
        val spec = ctx.resolve(ref).singleOrNull() as? GoTypeSpec ?: return
        val target = ctx.typeOf(expression)
        if (target is GoTypeParamType || (target.underlying() as? GoBasicType)?.kind != GoBasicKind.STRING) return
        val source = ctx.typeOf(arg)
        if (source is GoTypeParamType) return
        val kind = (source.underlying() as? GoBasicType)?.kind ?: return
        if (!kind.isInteger || kind == GoBasicKind.UINT8 || kind == GoBasicKind.INT32 || kind == GoBasicKind.UNTYPED_RUNE) return
        val targetName = describe(spec.name ?: return, target)
        val sourceName = describe(source) ?: return
        val callee = expression.expression.text
        val argText = arg.text
        val exact = GoLintPsi.isBuiltin(spec) && spec.name == "string"
        val decimal = if (source is GoNamedType && source.methods.isNotEmpty()) null else GoReplaceWithImportFix("Format the number as a decimal", "fmt") { q ->
            if (exact) "${q}Sprint($argText)" else "$callee(${q}Sprint($argText))"
        }
        val rune = GoReplaceWithTextFix("Convert a single rune to a string", "$callee(rune($argText))")
        ctx.report(expression, "conversion from $sourceName to $targetName yields a string of one rune, not a string of digits", *listOfNotNull(decimal, rune).toTypedArray())
    }

    /** vet's `describe`: `int`, `untyped int`, `C (int)`. */
    private fun describe(type: GoType): String? = when (type) {
        is GoBasicType -> type.name
        is GoNamedType -> describe(type.name, type)
        else -> null
    }

    private fun describe(name: String, type: GoType): String {
        val under = (type.underlying() as? GoBasicType)?.name
        return if (under != null && under != name) "$name ($under)" else name
    }
}

/** govet `unsafeptr`: `unsafe.Pointer(u)` of a `uintptr` that is not pointer arithmetic, and `reflect.SliceHeader` / `StringHeader` values. */
class GoVetUnsafePointerRule : GoVetExpressionRule() {
    override val id: String get() = "govet:unsafeptr"
    override val title: String get() = "Possible misuse of unsafe.Pointer"
    override val description: String get() =
        "govet <code>unsafeptr</code>: a <code>uintptr</code> kept as a number is invisible to the garbage collector; converting it back to " +
            "<code>unsafe.Pointer</code> is only valid within one expression (<code>unsafe.Pointer(uintptr(p) + off)</code>), from " +
            "<code>reflect.Value.Pointer</code> / <code>UnsafeAddr</code> or a <code>*reflect.SliceHeader</code>'s <code>Data</code>."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        when (expression) {
            is GoCallExpr -> {
                val arg = GoStaticcheckPsi.arguments(expression)?.singleOrNull() ?: return
                if (!isConversionTo(expression, GoBasicKind.UNSAFE_POINTER, ctx)) return
                if (kind(ctx.typeOf(arg)) != GoBasicKind.UINTPTR) return
                if (isSafeUintptr(arg, ctx)) return
                ctx.report(expression, "possible misuse of unsafe.Pointer")
            }
            is GoUnaryExpr -> {
                val op = GoExpressionPsi.op(expression)
                val operand = expression.expression ?: return
                val header = when (op) {
                    "*" -> (ctx.typeOf(operand) as? GoPointerType)?.elem
                    "&" -> ctx.typeOf(operand)
                    else -> return
                } ?: return
                if (!GoExpressionPsi.isNamed(header, "reflect", "SliceHeader", "StringHeader")) return
                ctx.report(expression, "possible misuse of reflect.${(header as GoNamedType).name}")
            }
            else -> {}
        }
    }

    /** vet's `isSafeUintptr`. */
    private fun isSafeUintptr(x: GoExpression, ctx: GoRuleContext): Boolean {
        when (val u = GoLintPsi.unparen(x)) {
            is GoReferenceExpression -> {
                val q = u.expression
                if (q != null && u.identifier.text == "Data") {
                    val pt = ctx.typeOf(q) as? GoPointerType
                    if (pt != null && GoExpressionPsi.isNamed(pt.elem, "reflect", "SliceHeader", "StringHeader")) return true
                }
            }
            is GoCallExpr -> {
                val ref = GoLintPsi.unparen(u.expression) as? GoReferenceExpression
                val q = ref?.expression
                if (q != null && GoStaticcheckPsi.arguments(u)?.isEmpty() == true && ref.identifier.text in REFLECT_METHODS &&
                    GoExpressionPsi.isNamed(ctx.typeOf(q), "reflect", "Value")
                ) return true
            }
            else -> {}
        }
        return isSafeArith(x, ctx)
    }

    /** vet's `isSafeArith`: `uintptr(p)` of an `unsafe.Pointer`, plus or minus offsets, `&^` masks. */
    private fun isSafeArith(x: GoExpression, ctx: GoRuleContext): Boolean = when (val u = GoLintPsi.unparen(x)) {
        is GoCallExpr -> {
            val arg = GoStaticcheckPsi.arguments(u)?.singleOrNull()
            arg != null && isConversionTo(u, GoBasicKind.UINTPTR, ctx) && kind(ctx.typeOf(arg)) == GoBasicKind.UNSAFE_POINTER
        }
        is GoBinaryExpr -> when (GoExpressionPsi.op(u)) {
            "+", "-", "&^" -> isSafeArith(u.left, ctx) && u.right?.let { !isSafeArith(it, ctx) } == true
            else -> false
        }
        else -> false
    }

    /** `T(x)` where `T` names a type whose underlying type is the basic [kind]. */
    private fun isConversionTo(call: GoCallExpr, kind: GoBasicKind, ctx: GoRuleContext): Boolean =
        GoExpressionPsi.isConversion(call, ctx) && kind(ctx.typeOf(call)) == kind

    private fun kind(type: GoType): GoBasicKind? = if (type is GoTypeParamType) null else (type.underlying() as? GoBasicType)?.kind

    private companion object {
        val REFLECT_METHODS = setOf("Pointer", "UnsafeAddr")
    }
}
