package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import java.math.BigInteger

/** staticcheck SA1018: `strings.Replace(s, old, new, 0)` / `bytes.Replace(…, 0)` replaces nothing. */
class GoReplaceZeroRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1018"
    override val title: String get() = "Replace with n == 0"
    override val description: String get() =
        "<code>strings.Replace(s, old, new, 0)</code> and <code>bytes.Replace(…, 0)</code> replace nothing; -1 (or <code>ReplaceAll</code>) replaces every instance."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "strings.Replace" && callee != "bytes.Replace") return
        val n = arguments.getOrNull(3) ?: return
        if (GoStaticcheckPsi.intConstant(n, ctx) != BigInteger.ZERO) return
        ctx.report(n, "calling $callee with n == 0 will return no results, did you mean -1?", GoReplaceWithTextFix("Replace with -1", "-1"))
    }

    private companion object {
        val NAMES = setOf("Replace")
    }
}

/** staticcheck SA1024: a `Trim` / `TrimLeft` / `TrimRight` cutset with duplicate characters (a cutset is a set, not a prefix). */
class GoNonUniqueCutsetRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1024"
    override val title: String get() = "Cutset with duplicate characters"
    override val description: String get() =
        "The cutset of <code>strings.Trim</code>, <code>TrimLeft</code>, <code>TrimRight</code> (and the <code>bytes</code> ones) is a set of " +
            "characters; repeated characters suggest a prefix or suffix was meant: use <code>TrimPrefix</code> / <code>TrimSuffix</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee !in CALLEES) return
        val cutset = arguments.getOrNull(1) ?: return
        val text = GoStaticcheckPsi.stringConstant(cutset, ctx) ?: return
        val runes = text.codePoints().toArray()
        if (runes.size < 2 || runes.distinct().size == runes.size) return
        val distinct = runes.distinct().toIntArray()
        val unique = String(distinct, 0, distinct.size)
        // the fix rewrites only a plain literal: an escape or a named constant would change meaning or get lost
        val literal = cutset as? GoStringLiteral
        val plain = literal != null && '\\' !in literal.text && unique.none { it == '"' || it == '\n' || it == '\r' }
        val fixes = if (plain) arrayOf(GoReplaceWithTextFix("Remove duplicate characters", "\"$unique\"")) else emptyArray()
        ctx.report(cutset, "cutset contains duplicate characters", *fixes)
    }

    private companion object {
        val CALLEES = setOf("strings.Trim", "strings.TrimLeft", "strings.TrimRight", "bytes.Trim", "bytes.TrimLeft", "bytes.TrimRight")
        val NAMES = setOf("Trim", "TrimLeft", "TrimRight")
    }
}
