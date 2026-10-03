package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import java.math.BigInteger

/** staticcheck SA1000: a constant regular expression that `regexp` cannot compile; the message is Go's compile error. */
class GoInvalidRegexpRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1000"
    override val title: String get() = "Invalid regular expression"
    override val description: String get() =
        "A constant pattern passed to <code>regexp.Compile</code>, <code>MustCompile</code>, <code>Match</code>, <code>MatchReader</code> " +
            "or <code>MatchString</code> does not compile: <code>regexp.MustCompile(\"[\")</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee !in CALLEES) return
        val pattern = arguments.firstOrNull() ?: return
        val text = GoStaticcheckPsi.stringConstant(pattern, ctx) ?: return
        // a constant with invalid UTF-8 cannot be told apart from U+FFFD here
        if ('�' in text) return
        val error = GoRegexpSyntax.error(text) ?: return
        ctx.report(pattern, error)
    }

    private companion object {
        val CALLEES = setOf("regexp.Compile", "regexp.MustCompile", "regexp.Match", "regexp.MatchReader", "regexp.MatchString")
        val NAMES = CALLEES.map { it.substringAfterLast('.') }.toSet()
    }
}

/** staticcheck SA1010: `re.FindAll*(…, 0)` returns no results. */
class GoRegexpFindAllZeroRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1010"
    override val title: String get() = "FindAll with n == 0"
    override val description: String get() =
        "<code>(*regexp.Regexp).FindAll…(s, 0)</code> always returns nothing: <code>n</code> limits the number of matches, -1 means all."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (!callee.startsWith("regexp.Regexp.")) return
        val n = arguments.getOrNull(1) ?: return
        if (GoStaticcheckPsi.intConstant(n, ctx) != BigInteger.ZERO) return
        ctx.report(n, "calling a FindAll method with n == 0 will return no results, did you mean -1?", GoReplaceWithTextFix("Replace with -1", "-1"))
    }

    private companion object {
        val NAMES = setOf(
            "FindAll", "FindAllIndex", "FindAllString", "FindAllStringIndex", "FindAllStringSubmatch", "FindAllStringSubmatchIndex",
            "FindAllSubmatch", "FindAllSubmatchIndex",
        )
    }
}
