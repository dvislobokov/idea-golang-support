package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import java.math.BigInteger

/** staticcheck SA1030: a constant `base`, `bitSize` or `fmt` argument of `strconv` that the function rejects (it panics or errors at run time). */
class GoStrconvArgumentsRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1030"
    override val title: String get() = "Invalid strconv argument"
    override val description: String get() =
        "A constant argument of <code>strconv</code> out of its range: <code>ParseInt(s, 1, 64)</code> (base 0 or 2..36), <code>ParseFloat(s, 16)</code> " +
            "(bitSize 32 or 64), <code>FormatFloat(f, 'z', -1, 64)</code> (unknown format)."
    override val calleeNames: Set<String> get() = NAMES

    private enum class Kind { PARSE_BASE, FORMAT_BASE, INT_BITS, FLOAT_BITS, COMPLEX_BITS, FORMAT }

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (!callee.startsWith("strconv.")) return
        val checks = CHECKS[callee.removePrefix("strconv.")] ?: return
        for ((index, kind) in checks) {
            val argument = arguments.getOrNull(index) ?: continue
            val value = GoStaticcheckPsi.intConstant(argument, ctx) ?: continue
            val message = problem(kind, value) ?: continue
            ctx.report(argument, message)
        }
    }

    private fun problem(kind: Kind, value: BigInteger): String? {
        val v = if (value.bitLength() < 32) value.toInt() else if (value.signum() < 0) Int.MIN_VALUE else Int.MAX_VALUE
        return when (kind) {
            Kind.PARSE_BASE -> when {
                v == 0 -> null
                v < 2 -> "'base' must not be smaller than 2, unless it is 0"
                v > 36 -> "'base' must not be larger than 36"
                else -> null
            }
            Kind.FORMAT_BASE -> when {
                v < 2 -> "'base' must not be smaller than 2"
                v > 36 -> "'base' must not be larger than 36"
                else -> null
            }
            Kind.INT_BITS -> if (v < 0 || v > 64) "'bitSize' argument is invalid, must be within 0 and 64" else null
            Kind.FLOAT_BITS -> if (v != 32 && v != 64) "'bitSize' argument is invalid, must be either 32 or 64" else null
            Kind.COMPLEX_BITS -> if (v != 64 && v != 128) "'bitSize' argument is invalid, must be either 64 or 128" else null
            Kind.FORMAT -> if (v.toChar() in "beEfgGxX" && v in 0..0x7f) null else "'fmt' argument is invalid: unknown format ${runeQuote(v)}"
        }
    }

    /** Go's `%q` of an integer: a single-quoted character literal. */
    private fun runeQuote(v: Int): String {
        if (v < 0 || v > Character.MAX_CODE_POINT) return "'\\ufffd'"
        val quoted = GoStaticcheckPsi.quote(String(Character.toChars(v)))
        val body = quoted.substring(1, quoted.length - 1).replace("\\\"", "\"").let { if (it == "'") "\\'" else it }
        return "'$body'"
    }

    private companion object {
        val CHECKS: Map<String, List<Pair<Int, Kind>>> = mapOf(
            "ParseComplex" to listOf(1 to Kind.COMPLEX_BITS),
            "ParseFloat" to listOf(1 to Kind.FLOAT_BITS),
            "ParseInt" to listOf(1 to Kind.PARSE_BASE, 2 to Kind.INT_BITS),
            "ParseUint" to listOf(1 to Kind.PARSE_BASE, 2 to Kind.INT_BITS),
            "FormatComplex" to listOf(1 to Kind.FORMAT, 3 to Kind.COMPLEX_BITS),
            "FormatFloat" to listOf(1 to Kind.FORMAT, 3 to Kind.FLOAT_BITS),
            "FormatInt" to listOf(1 to Kind.FORMAT_BASE),
            "FormatUint" to listOf(1 to Kind.FORMAT_BASE),
            "AppendFloat" to listOf(2 to Kind.FORMAT, 4 to Kind.FLOAT_BITS),
            "AppendInt" to listOf(2 to Kind.FORMAT_BASE),
            "AppendUint" to listOf(2 to Kind.FORMAT_BASE),
        )
        val NAMES = CHECKS.keys
    }
}
