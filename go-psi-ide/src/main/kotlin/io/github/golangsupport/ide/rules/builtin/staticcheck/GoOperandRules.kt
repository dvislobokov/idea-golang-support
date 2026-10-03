package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoMulExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant
import java.math.BigInteger

/** staticcheck SA4000: the same expression on both sides of `==`, `-`, `&&`, `<`... (`x == x`, `a - a`). */
class GoIdenticalOperandsRule : GoStaticcheckExpressionRule() {
    override val id: String get() = ID
    override val title: String get() = "Binary operator has identical expressions on both sides"
    override val description: String get() =
        "<code>x == x</code>, <code>a &amp;&amp; a</code>, <code>n - n</code>: the same expression on both sides of an operator is usually a typo. " +
            "Floats are left alone (<code>x != x</code> tests for NaN), so are operands with calls or receives."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoBinaryExpr) return
        val op = GoExpressionPsi.op(expression) ?: return
        if (op !in OPS) return
        val left = expression.left
        val right = expression.right ?: return
        if (left.javaClass != right.javaClass) return
        if (!GoExpressionPsi.sameCode(left, right)) return
        if ((op == "==" || op == "!=") && isComparableCheck(expression, left, right)) return
        if (GoExpressionPsi.mayBeFloat(ctx.typeOf(left))) return
        // staticcheck flags f() == f(); calls and receives may differ between the two evaluations, so they are skipped here
        if (!GoExpressionPsi.noEffects(left, ctx)) return
        ctx.report(expression, "identical expressions on the left and right side of the '$op' operator")
    }

    /** `var _ = T{} == T{}`: a compile-time check that `T` is comparable. */
    private fun isComparableCheck(e: GoBinaryExpr, left: GoExpression, right: GoExpression): Boolean {
        if (!isEmptyLiteral(left) || !isEmptyLiteral(right)) return false
        val spec = e.parent as? GoVarSpec ?: return false
        val index = spec.expressionList.indexOf(e)
        return index >= 0 && spec.varDefinitionList.getOrNull(index)?.name == "_"
    }

    private fun isEmptyLiteral(e: GoExpression): Boolean = e is GoCompositeLit && e.literalValue.text.filterNot { it.isWhitespace() } == "{}"

    companion object {
        const val ID = "SA4000"
        private val OPS = setOf("==", "!=", "-", "/", "&", "%", "|", "^", "&^", "&&", "||", "<", ">", "<=", ">=")
    }
}

/** staticcheck SA4001: `&*x` and `*&x` are `x`; they do not copy. */
class GoIneffectiveCopyRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4001"
    override val title: String get() = "&*x gets simplified to x, it does not copy x"
    override val description: String get() = "<code>&amp;*p</code> is <code>p</code> and <code>*&amp;x</code> is <code>x</code>: neither makes a copy. To copy, write <code>v := *p; q := &amp;v</code>."
    override val needs: Set<GoRuleNeed> get() = SYNTAX_ONLY

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoUnaryExpr) return
        val outer = GoExpressionPsi.op(expression)
        if (outer != "&" && outer != "*") return
        val inner = expression.expression as? GoUnaryExpr ?: return
        val innerOp = GoExpressionPsi.op(inner)
        val operand = inner.expression ?: return
        if (outer == "&" && innerOp == "*") {
            if (operand is GoReferenceExpression && operand.expression == null && CGO.matches(operand.identifier.text)) return
            ctx.report(expression, "&*x will be simplified to x. It will not copy x.", GoReplaceWithTextFix("Simplify &*x to x", operand.text))
        } else if (outer == "*" && innerOp == "&") {
            ctx.report(expression, "*&x will be simplified to x. It will not copy x.", GoReplaceWithTextFix("Simplify *&x to x", operand.text))
        }
    }

    private companion object {
        /** cgo produces `fn(&*_Cvar_kSomeCallbacks)`. */
        val CGO = Regex("^_C(func|var)_.+$")
    }
}

/** staticcheck SA4013: `!!b` negates twice. */
class GoDoubleNegationRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4013"
    override val title: String get() = "Negating a boolean twice has no effect"
    override val description: String get() = "<code>!!b</code> is <code>b</code>: one of the negations is probably a typo."
    override val needs: Set<GoRuleNeed> get() = SYNTAX_ONLY

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoUnaryExpr || GoExpressionPsi.op(expression) != "!") return
        val single = expression.expression as? GoUnaryExpr ?: return
        if (GoExpressionPsi.op(single) != "!") return
        val x = single.expression ?: return
        ctx.report(expression, "negating a boolean twice has no effect; is this a typo?",
            GoReplaceWithTextFix("Turn into single negation", single.text), GoReplaceWithTextFix("Remove double negation", x.text))
    }
}

/** staticcheck SA4016: `x & 0`, `x | 0`, `x ^ 0` (also with a constant defined as `iota` that is 0). */
class GoSillyBitwiseRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4016"
    override val title: String get() = "Certain bitwise operations, such as x ^ 0, do not do anything useful"
    override val description: String get() =
        "<code>x &amp; 0</code> is always 0, <code>x | 0</code> and <code>x ^ 0</code> are <code>x</code>. Often a flag constant declared as " +
            "<code>iota</code> (0) where <code>1 &lt;&lt; iota</code> was meant. Shifts by 0 are left alone (<code>x&lt;&lt;0, x&lt;&lt;8, ...</code>)."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoBinaryExpr) return
        val op = GoExpressionPsi.op(expression) ?: return
        if (op != "&" && op != "|" && op != "^") return
        val y = expression.right ?: return
        val x = expression.left
        val literal = GoExpressionPsi.isIntLiteral(y, BigInteger.ZERO)
        val iota = !literal && y is GoReferenceExpression && y.expression == null
        if (!literal && !iota) return
        // the operand too: with an unknown x the expression may still type as the untyped constant
        for (t in listOf(ctx.typeOf(expression), ctx.typeOf(x))) {
            val types = GoExpressionPsi.typeSet(t) ?: return
            if (!types.all { (it.underlying() as? GoBasicType)?.kind?.isInteger == true }) return
        }
        val node = GoExpressionPsi.render(expression)
        if (iota) {
            if (!isIotaZero(y as GoReferenceExpression, ctx)) return
            val name = GoExpressionPsi.render(y)
            val result = if (op == "&") "0" else GoExpressionPsi.render(x)
            ctx.report(expression, "$node always equals $result; $name is defined as iota and has value 0, maybe $name is meant to be 1 << iota?")
        } else if (op == "&") {
            ctx.report(expression, "$node always equals 0")
        } else {
            ctx.report(expression, "$node always equals ${GoExpressionPsi.render(x)}", GoReplaceWithTextFix("Replace with ${GoExpressionPsi.render(x)}", x.text))
        }
    }

    /** A constant of this package declared alone as `Name = iota` with value 0. */
    private fun isIotaZero(ref: GoReferenceExpression, ctx: GoRuleContext): Boolean {
        val target = ctx.resolve(ref).singleOrNull() as? GoConstDefinition ?: return false
        if (GoLintPsi.isBuiltin(target)) return false
        if (GoAnalysisPsi.packagePath(target) != GoAnalysisPsi.packagePath(ctx.file)) return false // dot-imported
        if ((ctx.semantic.constantValue(ref) as? GoConstant.Int)?.value != BigInteger.ZERO) return false
        val spec = target.parent as? GoConstSpec ?: return false
        if (spec.constDefinitionList.size != 1 || spec.expressionList.size != 1) return false
        val value = spec.expressionList[0] as? GoReferenceExpression ?: return false
        return value.identifier.text == "iota" && GoExpressionPsi.builtinName(value, ctx) == "iota"
    }
}

/** staticcheck SA4022: `&x == nil` is always false. */
class GoAddressIsNilRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4022"
    override val title: String get() = "Comparing the address of a variable against nil"
    override val description: String get() = "<code>&amp;x == nil</code> is always false: the address of a variable is never nil."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoBinaryExpr) return
        val left = expression.left as? GoUnaryExpr ?: return
        if (GoExpressionPsi.op(left) != "&") return
        val op = GoExpressionPsi.op(expression)
        if (op != "==" && op != "!=") return
        val right = expression.right as? GoReferenceExpression ?: return
        if (right.identifier.text != "nil" || GoExpressionPsi.builtinName(right, ctx) != "nil") return
        ctx.report(expression, "the address of a variable cannot be nil")
    }
}

/** staticcheck SA4025: `1 / 2` of two integer literals is 0. */
class GoIntegerDivisionZeroRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4025"
    override val title: String get() = "Integer division of literals that results in zero"
    override val description: String get() =
        "<code>1 / 2</code> divides two integer constants: the result is 0 even where a float is expected. Write <code>1.0 / 2</code>."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoMulExpr || expression.quo == null) return
        if (!GoExpressionPsi.isIntLiteral(expression.left) || !GoExpressionPsi.isIntLiteral(expression.right)) return
        val value = ctx.semantic.constantValue(expression)?.toBigInteger() ?: return
        if (value != BigInteger.ZERO) return
        ctx.report(expression, "the integer division '${GoExpressionPsi.render(expression)}' results in zero")
    }
}

/** staticcheck SA4026: `-0.0`, `float64(-0)`: Go constants have no negative zero. Fix: `math.Copysign(0, -1)`. */
class GoNegativeZeroRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4026"
    override val title: String get() = "Go constants cannot express negative zero"
    override val description: String get() =
        "Constant arithmetic is exact: <code>-0.0</code> is 0, not IEEE negative zero. Use <code>math.Copysign(0, -1)</code>."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        when (expression) {
            is GoUnaryExpr -> {
                if (GoExpressionPsi.op(expression) != "-") return
                when (val operand = expression.expression) {
                    is GoLiteral -> if (operand.float != null && operand.text == "0.0") {
                        ctx.report(expression, "in Go, the floating-point literal '-0.0' is the same as '0.0', it does not produce a negative zero", fix("float64"))
                    }
                    is GoCallExpr -> {
                        val conv = conversion(operand, ctx) ?: return
                        val lit = GoStaticcheckPsi.arguments(operand)?.singleOrNull() as? GoLiteral ?: return
                        if (!(lit.int != null && lit.text == "0" || lit.float != null && lit.text == "0.0")) return
                        report(expression, conv, lit, ctx)
                    }
                    else -> {}
                }
            }
            is GoCallExpr -> {
                if (GoLintPsi.calleeReference(expression)?.identifier?.text !in FLOATS) return
                val arg = GoStaticcheckPsi.arguments(expression)?.singleOrNull() as? GoUnaryExpr ?: return
                if (GoExpressionPsi.op(arg) != "-") return
                val lit = arg.expression as? GoLiteral ?: return
                if (lit.int == null || lit.text != "0") return
                report(expression, conversion(expression, ctx) ?: return, lit, ctx)
            }
            else -> {}
        }
    }

    private fun report(node: GoExpression, conv: String, lit: GoLiteral, ctx: GoRuleContext) = ctx.report(node,
        "in Go, the floating-point expression '${GoExpressionPsi.render(node)}' is the same as '$conv(${lit.text})', it does not produce a negative zero", fix(conv))

    /** `float32` / `float64` when [call] converts with the predeclared type. */
    private fun conversion(call: GoCallExpr, ctx: GoRuleContext): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        return GoExpressionPsi.builtinName(callee, ctx)?.takeIf { it in FLOATS }
    }

    private fun fix(conv: String) = GoReplaceWithImportFix("Use math.Copysign to create negative zero", "math") { q ->
        if (conv == "float32") "float32(${q}Copysign(0, -1))" else "${q}Copysign(0, -1)"
    }

    private companion object {
        val FLOATS = setOf("float32", "float64")
    }
}

/** staticcheck SA4028: `x % 1` is always zero. */
class GoModuloOneRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4028"
    override val title: String get() = "x % 1 is always zero"
    override val description: String get() = "The remainder of a division by 1 is always 0; a different modulus was probably meant."
    override val needs: Set<GoRuleNeed> get() = SYNTAX_ONLY

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoMulExpr || expression.rem == null) return
        if (!GoExpressionPsi.isIntLiteral(expression.right, BigInteger.ONE)) return
        ctx.report(expression, "x % 1 is always zero")
    }
}

