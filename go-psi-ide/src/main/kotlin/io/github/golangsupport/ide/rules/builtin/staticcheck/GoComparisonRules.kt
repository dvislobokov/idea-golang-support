package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoConstraintExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import java.math.BigInteger

/** staticcheck SA4003: `u < 0` for unsigned `u`, `x > math.MaxInt8` for an `int8`: comparisons that are always true or false. */
class GoExtremeComparisonRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4003"
    override val title: String get() = "Comparing unsigned values against negative values is pointless"
    override val description: String get() =
        "<code>u &lt; 0</code> is never true and <code>u &gt;= 0</code> always is for unsigned <code>u</code>; likewise comparisons past " +
            "<code>math.MaxInt8</code>, <code>math.MinInt16</code>, ... of the operand's type. Only literals and the <code>math</code> constants are checked."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoConditionalExpr) return
        val op0 = GoExpressionPsi.op(expression) ?: return
        if (op0 == "==" || op0 == "!=") return
        val right = expression.right ?: return
        val left = expression.left
        // go/types records the converted type of an untyped constant operand; take the typed side
        val tx = ctx.typeOf(left).let { if (GoTypePredicates.isUntyped(it)) ctx.typeOf(right) else it }
        if (GoTypePredicates.isUntyped(tx)) return
        val types = GoExpressionPsi.typeSet(tx) ?: return
        val kinds = types.map { (it.underlying() as? GoBasicType)?.kind }
        val shown = GoExpressionPsi.typeString(tx, ctx)
        val allUnsigned = kinds.all { it != null && it.isUnsigned }
        if (allUnsigned) {
            if (op0 == "<" && zero(right) || op0 == ">" && zero(left)) ctx.report(expression, "no value of type $shown is less than 0")
            if (op0 == ">=" && zero(right) || op0 == "<=" && zero(left)) ctx.report(expression, "every value of type $shown is >= 0")
        }
        val core = if (tx is GoTypeParamType) tx.coreType else tx.underlying()
        val bounds = BOUNDS[(core as? GoBasicType)?.kind ?: return] ?: return
        var x = left
        var y = right
        val op = when (op0) {
            ">=", ">" -> op0
            "<=" -> { x = right; y = left; ">=" }
            "<" -> { x = right; y = left; ">" }
            else -> return
        }
        if (isMax(y, bounds, ctx)) ctx.report(expression, "no value of type $shown is greater than ${bounds.maxConst}")
        if (op == ">=" && isMax(x, bounds, ctx)) ctx.report(expression, "every value of type $shown is <= ${bounds.maxConst}")
        if (!allUnsigned && bounds.minConst != null) {
            if (isMin(x, bounds, ctx)) ctx.report(expression, "no value of type $shown is less than ${bounds.minConst}")
            if (op == ">=" && isMin(y, bounds, ctx)) ctx.report(expression, "every value of type $shown is >= ${bounds.minConst}")
        }
    }

    private fun zero(e: GoExpression): Boolean = GoExpressionPsi.isIntLiteral(e, BigInteger.ZERO)

    private fun isMax(e: GoExpression, b: Bounds, ctx: GoRuleContext): Boolean = isConst(e, b.maxConst, ctx) || GoExpressionPsi.isIntLiteral(e, b.max)

    private fun isMin(e: GoExpression, b: Bounds, ctx: GoRuleContext): Boolean =
        b.minConst != null && (isConst(e, b.minConst, ctx) || GoExpressionPsi.isIntLiteral(e, b.min))

    /** `math.MaxUint8` written as a selector (staticcheck matches selectors only, not dot imports). */
    private fun isConst(e: GoExpression, name: String, ctx: GoRuleContext): Boolean {
        if (e !is GoReferenceExpression || e.expression == null || e.identifier.text != name.substringAfter('.')) return false
        return GoStaticcheckPsi.referenceKey(e, ctx) == name
    }

    private class Bounds(val maxConst: String, val max: BigInteger, val minConst: String?, val min: BigInteger)

    private companion object {
        fun signed(bits: Int) = Bounds("math.MaxInt$bits", BigInteger.TWO.pow(bits - 1) - BigInteger.ONE, "math.MinInt$bits", -BigInteger.TWO.pow(bits - 1))
        fun unsigned(bits: Int) = Bounds("math.MaxUint$bits", BigInteger.TWO.pow(bits) - BigInteger.ONE, null, BigInteger.ZERO)

        // int and uint as on 64-bit targets, like staticcheck
        val BOUNDS = mapOf(
            GoBasicKind.UINT8 to unsigned(8), GoBasicKind.UINT16 to unsigned(16), GoBasicKind.UINT32 to unsigned(32), GoBasicKind.UINT64 to unsigned(64),
            GoBasicKind.UINT to unsigned(64), GoBasicKind.INT8 to signed(8), GoBasicKind.INT16 to signed(16), GoBasicKind.INT32 to signed(32),
            GoBasicKind.INT64 to signed(64), GoBasicKind.INT to signed(64),
        )
    }
}

/** staticcheck SA4012: comparing with `math.NaN()` is always false (`!=`: true). Fix for `==` / `!=`: `math.IsNaN(x)`. */
class GoNaNComparisonRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4012"
    override val title: String get() = "Comparing a value against NaN even though no value is equal to NaN"
    override val description: String get() = "<code>x == math.NaN()</code> is always false, even for NaN. Use <code>math.IsNaN(x)</code>."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoConditionalExpr) return
        val op = GoExpressionPsi.op(expression) ?: return
        val right = expression.right ?: return
        val left = expression.left
        val leftNaN = nanCall(left, ctx)
        val rightNaN = nanCall(right, ctx)
        val nan = leftNaN ?: rightNaN ?: return
        val other = if (leftNaN != null) right else left
        val fixes = if ((op == "==" || op == "!=") && (leftNaN == null || rightNaN == null)) {
            val qualifier = (nan.expression as? GoReferenceExpression)?.expression?.let { "${it.text}." } ?: ""
            val not = if (op == "!=") "!" else ""
            arrayOf(GoReplaceWithTextFix("Use math.IsNaN", "$not${qualifier}IsNaN(${other.text})"))
        } else emptyArray()
        ctx.report(expression, "no value is equal to NaN, not even NaN itself", *fixes)
    }

    /** The `math.NaN()` call [e] is (parentheses allowed). */
    private fun nanCall(e: GoExpression, ctx: GoRuleContext): GoCallExpr? {
        val call = GoLintPsi.unparen(e) as? GoCallExpr ?: return null
        val ref = GoLintPsi.calleeReference(call) ?: return null
        if (ref.identifier.text != "NaN" || GoStaticcheckPsi.arguments(call)?.isEmpty() != true) return null
        return call.takeIf { GoStaticcheckPsi.calleeKey(ref, ctx) == "math.NaN" }
    }
}

/** staticcheck SA4024: `len(x) < 0` / `0 > cap(x)` is never true. */
class GoBuiltinNegativeRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4024"
    override val title: String get() = "Checking for impossible return value from a builtin function"
    override val description: String get() = "<code>len</code> and <code>cap</code> never return negative values: <code>len(x) &lt; 0</code> is always false."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoConditionalExpr) return
        val op = GoExpressionPsi.op(expression)
        val right = expression.right ?: return
        val call = when {
            op == "<" && GoExpressionPsi.isIntLiteral(right, BigInteger.ZERO) -> expression.left
            op == ">" && GoExpressionPsi.isIntLiteral(expression.left, BigInteger.ZERO) -> right
            else -> return
        } as? GoCallExpr ?: return
        val name = GoExpressionPsi.builtinName(call.expression, ctx) ?: return
        if (name != "len" && name != "cap") return
        ctx.report(expression, "builtin function $name does not return negative values")
    }
}

/** staticcheck SA4032: `runtime.GOOS == "windows"` in a file whose build constraints exclude windows. */
class GoImpossibleGoosRule : GoStaticcheckExpressionRule() {
    override val id: String get() = "SA4032"
    override val title: String get() = "Comparing runtime.GOOS or runtime.GOARCH against impossible value"
    override val description: String get() =
        "The file's <code>//go:build</code> line and <code>_GOOS</code> / <code>_GOARCH</code> name suffix rule out the compared value: the branch is dead."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoConditionalExpr) return
        val left = expression.left as? GoReferenceExpression ?: return
        val name = left.identifier.text
        if (name != "GOOS" && name != "GOARCH") return
        val op = GoExpressionPsi.op(expression)
        if (op != "==" && op != "!=") return
        val literal = expression.right as? GoStringLiteral ?: return
        if (GoStaticcheckPsi.referenceKey(left, ctx) != "runtime.$name") return
        val value = GoStaticcheckPsi.stringConstant(literal, ctx) ?: return
        val constraints = constraints(ctx) ?: return
        val goos = name == "GOOS"
        if (value !in (if (goos) KNOWN_GOOS else KNOWN_GOARCH)) return // a newer or forked toolchain may know it
        val sat = satisfiable(constraints) { tag -> if (goos) matchGoos(tag, value) else matchGoarch(tag, value) } ?: return
        if (!sat) ctx.report(expression, "due to the file's build constraints, runtime.$name will never equal ${GoStaticcheckPsi.quote(value)}")
    }

    /** The `//go:build` expression of the file and-ed with the constraint of its name; null when there is none. */
    private fun constraints(ctx: GoRuleContext): GoConstraintExpr? {
        val header = ctx.file.buildConstraint.goBuild?.let { runCatching { GoBuildConstraintEvaluator.parseExpr(it) }.getOrNull() }
        val byName = fromName(GoPsiUtil.originalVirtualFile(ctx.file).name)
        return when {
            header == null -> byName
            byName == null -> header
            else -> GoConstraintExpr.And(header, byName)
        }
    }

    /** staticcheck `constraintsFromName`. */
    private fun fromName(file: String): GoConstraintExpr? {
        val name = file.removeSuffix(".go").removeSuffix("_test")
        var goos: String? = null
        var goarch: String? = null
        when (name.count { it == '_' }) {
            0 -> {}
            1 -> {
                val c = name.substringAfter('_')
                if (c in KNOWN_GOOS) goos = c else if (c in KNOWN_GOARCH) goarch = c
            }
            else -> {
                val n = name.lastIndexOf('_')
                val last = name.substring(n + 1)
                if (last in KNOWN_GOOS) goos = last
                else if (last in KNOWN_GOARCH) {
                    goarch = last
                    val c = name.substring(0, n).substringAfter('_')
                    if (c in KNOWN_GOOS) goos = c
                }
            }
        }
        val os = goos?.let { GoConstraintExpr.Tag(it) }
        val arch = goarch?.let { GoConstraintExpr.Tag(it) }
        return when {
            os != null && arch != null -> GoConstraintExpr.And(os, arch)
            else -> os ?: arch
        }
    }

    /**
     * staticcheck `validateTagComparison`: whether the constraint can hold with the compared value, trying every assignment of the
     * other tags (at most 10 of them); null when there are too many to try. [match] is (matched, special) for a tag.
     */
    private fun satisfiable(expr: GoConstraintExpr, match: (String) -> Pair<Boolean, Boolean>): Boolean? {
        val others = LinkedHashMap<String, Int>()
        val b = expr.eval { tag ->
            val (ok, special) = match(tag)
            if (!special) others.getOrPut(tag) { others.size }
            ok
        }
        if (b || others.isEmpty()) return b
        if (others.size > 10) return null
        for (bits in 0 until (1 shl others.size)) {
            val r = expr.eval { tag ->
                val (ok, special) = match(tag)
                if (special) ok else bits and (1 shl others.getValue(tag)) != 0
            }
            if (r) return true
        }
        return false
    }

    private fun matchGoos(tag: String, goos: String): Pair<Boolean, Boolean> = when (tag) {
        "aix", "android", "dragonfly", "freebsd", "hurd", "illumos", "ios", "js", "netbsd", "openbsd", "plan9", "wasip1", "windows" -> (goos == tag) to true
        "darwin" -> (goos == "darwin" || goos == "ios") to true
        "linux" -> (goos == "linux" || goos == "android") to true
        "solaris" -> (goos == "solaris" || goos == "illumos") to true
        "unix" -> (goos in UNIX) to true
        else -> false to false
    }

    private fun matchGoarch(tag: String, goarch: String): Pair<Boolean, Boolean> =
        if (tag in KNOWN_GOARCH) (goarch == tag) to true else false to false

    private companion object {
        /** staticcheck `knowledge.KnownGOOS` / `KnownGOARCH` (v0.8.1). */
        val KNOWN_GOOS = setOf(
            "aix", "android", "darwin", "dragonfly", "freebsd", "hurd", "illumos", "ios", "js", "linux", "netbsd", "openbsd", "plan9", "solaris",
            "wasip1", "windows",
        )
        val KNOWN_GOARCH = setOf(
            "386", "amd64", "arm", "arm64", "loong64", "mips", "mipsle", "mips64", "mips64le", "ppc64", "ppc64le", "riscv64", "s390x", "sparc64", "wasm",
        )
        val UNIX = setOf("aix", "android", "darwin", "dragonfly", "freebsd", "hurd", "illumos", "ios", "linux", "netbsd", "openbsd", "solaris")
    }
}
