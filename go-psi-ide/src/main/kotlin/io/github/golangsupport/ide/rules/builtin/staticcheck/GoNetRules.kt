package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression

/** staticcheck SA1007: a constant URL passed to `url.Parse` that does not parse. */
class GoInvalidUrlRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1007"
    override val title: String get() = "Invalid URL in url.Parse"
    override val description: String get() = "A constant string passed to <code>net/url.Parse</code> is not a valid URL: <code>url.Parse(\"http://%zz\")</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "net/url.Parse") return
        val argument = arguments.singleOrNull() ?: return
        val text = GoStaticcheckPsi.stringConstant(argument, ctx) ?: return
        val error = GoNetSyntax.urlError(text) ?: return
        ctx.report(argument, "${GoStaticcheckPsi.quote(text)} is not a valid URL: $error")
    }

    private companion object {
        val NAMES = setOf("Parse")
    }
}

/** staticcheck SA1020: a constant address of `http.ListenAndServe` / `ListenAndServeTLS` that is not a valid `host:port`. */
class GoInvalidListenAddressRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1020"
    override val title: String get() = "Invalid host:port"
    override val description: String get() =
        "The constant address of <code>http.ListenAndServe</code> / <code>ListenAndServeTLS</code> has no valid port or service name: " +
            "<code>http.ListenAndServe(\"localhost\", nil)</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "net/http.ListenAndServe" && callee != "net/http.ListenAndServeTLS") return
        val address = arguments.firstOrNull() ?: return
        val text = GoStaticcheckPsi.stringConstant(address, ctx) ?: return
        if (GoNetSyntax.validHostPort(text)) return
        ctx.report(address, "invalid port or service name in host:port pair")
    }

    private companion object {
        val NAMES = setOf("ListenAndServe", "ListenAndServeTLS")
    }
}

/** staticcheck SA1021: `bytes.Equal` on two `net.IP`s: the same address has a 4- and a 16-byte form. */
class GoBytesEqualIpRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1021"
    override val title: String get() = "bytes.Equal on net.IP"
    override val description: String get() =
        "<code>bytes.Equal(ip1, ip2)</code> on <code>net.IP</code> values: an IPv4 address in 4- and 16-byte form differs byte-wise; use <code>ip1.Equal(ip2)</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "bytes.Equal" || arguments.size != 2) return
        val (a, b) = arguments
        if (!GoAnalysisPsi.isNamed(ctx.typeOf(a), "net", "IP") || !GoAnalysisPsi.isNamed(ctx.typeOf(b), "net", "IP")) return
        val replacement = "${GoStaticcheckPsi.operandText(a)}.Equal(${b.text})"
        ctx.report(call, "use net.IP.Equal to compare net.IPs, not bytes.Equal", GoReplaceWithTextFix("Use net.IP.Equal", replacement))
    }

    private companion object {
        val NAMES = setOf("Equal")
    }
}
