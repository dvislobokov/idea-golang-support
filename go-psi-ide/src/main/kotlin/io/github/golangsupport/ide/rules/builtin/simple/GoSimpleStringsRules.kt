package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoSliceType
import java.math.BigInteger

/**
 * staticcheck S1003: `strings.Index(s, x) != -1` (also `> -1`, `>= 0`, and the `IndexAny` / `IndexRune` variants of `strings` and
 * `bytes`) is `strings.Contains(s, x)`; `== -1` and `< 0` are its negation.
 */
class GoStringsIndexRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1003"
    override val title: String get() = "Replace call to strings.Index with strings.Contains"
    override val description: String get() = "<code>strings.Index(x, y) != -1</code> is <code>strings.Contains(x, y)</code>."

    private class Match(val call: GoCallExpr, val replacement: String, val positive: Boolean)

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val m = match(expression, ctx) ?: return
        val args = GoExpressionPsi.render(m.call.argumentList ?: return)
        val qualifier = GoSimplePsi.qualifier(m.call) ?: return
        val text = (if (m.positive) "" else "!") + qualifier + m.replacement + args
        ctx.report(expression, "should use $text instead",
            *GoRewriteFix.offer("Simplify use of $qualifier${GoSimplePsi.calleeName(m.call)}", expression, ctx, ::fix))
    }

    private fun match(e: PsiElement, ctx: GoRuleContext): Match? {
        if (e !is GoConditionalExpr) return null
        val op = GoExpressionPsi.op(e) ?: return null
        val call = e.left as? GoCallExpr ?: return null
        val name = GoSimplePsi.calleeName(call) ?: return null
        val replacement = REPLACEMENTS[name] ?: return null
        val right = e.right ?: return null
        val value = (ctx.semantic.constantValue(right) as? GoConstant.Int)?.value ?: return null
        val positive = when (value) {
            MINUS_ONE -> mapOf(">" to true, "!=" to true, "==" to false)[op]
            BigInteger.ZERO -> mapOf(">=" to true, "<" to false)[op]
            else -> null
        } ?: return null
        if (GoSimplePsi.qualifier(call).isNullOrEmpty()) return null
        val key = GoSimplePsi.callee(call, REPLACEMENTS.keys, ctx) ?: return null
        if (key != "strings.$name" && key != "bytes.$name") return null
        return Match(call, replacement, positive)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val m = match(element, ctx) ?: return null
        val list = m.call.argumentList ?: return null
        if (GoSimplePsi.commentsOutside(element, listOf(list.textRange))) return null
        val qualifier = GoSimplePsi.qualifier(m.call) ?: return null
        return GoSimplePsi.replaceWith(element, (if (m.positive) "" else "!") + qualifier + m.replacement + list.text)
    }

    private companion object {
        val MINUS_ONE: BigInteger = BigInteger.ONE.negate()
        val REPLACEMENTS = mapOf("Index" to "Contains", "IndexAny" to "ContainsAny", "IndexRune" to "ContainsRune")
    }
}

/** staticcheck S1004: `bytes.Compare(a, b) == 0` is `bytes.Equal(a, b)`, `!= 0` its negation. */
class GoBytesCompareRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1004"
    override val title: String get() = "Replace call to bytes.Compare with bytes.Equal"
    override val description: String get() = "<code>bytes.Compare(x, y) == 0</code> is <code>bytes.Equal(x, y)</code>."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val call = match(expression, ctx) ?: return
        val args = GoStaticcheckPsi.arguments(call) ?: return
        val prefix = if ((expression as GoConditionalExpr).neq != null) "!" else ""
        ctx.report(expression, "should use ${prefix}bytes.Equal(${args.joinToString(", ") { GoExpressionPsi.render(it) }}) instead",
            *GoRewriteFix.offer("Simplify use of bytes.Compare", expression, ctx, ::fix))
    }

    private fun match(e: PsiElement, ctx: GoRuleContext): GoCallExpr? {
        if (e !is GoConditionalExpr || e.eql == null && e.neq == null) return null
        val call = e.left as? GoCallExpr ?: return null
        if (!GoExpressionPsi.isIntLiteral(e.right, BigInteger.ZERO)) return null
        if (GoSimplePsi.callee(call, COMPARE, ctx) != "bytes.Compare") return null
        // the bytes package is free to use bytes.Compare as it sees fit
        if (GoLintPsi.packagePath(ctx.file) == "bytes") return null
        return call
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = match(element, ctx) ?: return null
        val list = call.argumentList ?: return null
        if (GoSimplePsi.commentsOutside(element, listOf(list.textRange))) return null
        val qualifier = GoSimplePsi.qualifier(call) ?: return null
        val prefix = if ((element as GoConditionalExpr).neq != null) "!" else ""
        return GoSimplePsi.replaceWith(element, "$prefix${qualifier}Equal${list.text}")
    }

    private companion object {
        val COMPARE = setOf("Compare")
    }
}

/** staticcheck S1007: a regular expression in an interpreted string whose only escapes are `\\` reads better as a raw string. */
class GoRegexpRawStringRule : GoSimpleCallRule() {
    override val id: String get() = "S1007"
    override val title: String get() = "Simplify regular expression by using raw string literal"
    override val description: String get() =
        "Raw string literals use backticks instead of quotation marks and do not support any escape sequences. This means that the backslash " +
            "can be used freely, without the need of escaping. Since regular expressions have their own escape sequences, raw strings can improve their readability."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val name = GoSimplePsi.calleeName(call) ?: return
        if (name !in NAMES) return
        val lit = GoSimplePsi.args(call).singleOrNull() as? GoStringLiteral ?: return
        if (lit.string == null || !isDoubleEscapedOnly(lit.text)) return
        val key = GoSimplePsi.callee(call, NAMES, ctx) ?: return
        if (key != "regexp.$name") return
        ctx.report(lit, "should use raw string (`...`) with $key to avoid having to escape twice",
            *GoRewriteFix.offer("Convert to raw string literal", lit, ctx) { e, _ -> raw(e) })
    }

    /** Contains `\\`, no backtick, and every backslash is half of a `\\` pair. */
    private fun isDoubleEscapedOnly(text: String): Boolean {
        if (!text.contains("\\\\") || text.contains('`')) return false
        var escaped = false
        for (c in text) {
            if (!escaped && c == '\\') escaped = true
            else if (escaped) {
                if (c != '\\') return false
                escaped = false
            }
        }
        return true
    }

    private fun raw(element: PsiElement): List<GoEditPlan.Edit>? {
        val lit = element as? GoStringLiteral ?: return null
        if (lit.string == null || !isDoubleEscapedOnly(lit.text)) return null
        return GoSimplePsi.replaceWith(lit, "`" + lit.text.substring(1, lit.text.length - 1).replace("\\\\", "\\") + "`")
    }

    private companion object {
        val NAMES = setOf("MustCompile", "Compile")
    }
}

/** staticcheck S1035: `Header.Add/Del/Get/Set` canonicalize the key themselves; `http.CanonicalHeaderKey` around it is redundant. */
class GoCanonicalHeaderKeyRule : GoSimpleCallRule() {
    override val id: String get() = "S1035"
    override val title: String get() = "Redundant call to net/http.CanonicalHeaderKey in method call on net/http.Header"
    override val description: String get() =
        "The methods on <code>net/http.Header</code>, namely <code>Add</code>, <code>Del</code>, <code>Get</code> and <code>Set</code>, already " +
            "canonicalize the given header name."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val name = GoSimplePsi.calleeName(call) ?: return
        if (name !in METHODS) return
        val arg = GoSimplePsi.args(call).firstOrNull() as? GoCallExpr ?: return
        if (GoSimplePsi.calleeName(arg) != "CanonicalHeaderKey") return
        if (GoSimplePsi.callee(call, METHODS, ctx) != "net/http.Header.$name") return
        if (!GoSimplePsi.isCallTo(arg, "net/http.CanonicalHeaderKey", ctx)) return
        ctx.report(arg, "calling net/http.CanonicalHeaderKey on the 'key' argument of (net/http.Header).$name is redundant",
            *GoRewriteFix.offer("Remove call to CanonicalHeaderKey", arg, ctx) { e, _ -> unwrap(e) })
    }

    private fun unwrap(element: PsiElement): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val inner = GoStaticcheckPsi.arguments(call)?.singleOrNull() ?: return null
        if (GoSimplePsi.commentsOutside(call, listOf(inner.textRange))) return null
        return GoSimplePsi.replaceWith(call, inner.text)
    }

    private companion object {
        val METHODS = setOf("Add", "Del", "Get", "Set")
    }
}

/** staticcheck SA6005: `strings.ToLower(a) == strings.ToLower(b)` allocates two strings; `strings.EqualFold(a, b)` does not. */
class GoToLowerComparisonRule : GoSimpleExpressionRule() {
    override val id: String get() = "SA6005"
    override val linterAliases: Set<String> get() = emptySet()
    override val title: String get() = "Inefficient string comparison with strings.ToLower or strings.ToUpper"
    override val description: String get() =
        "Converting two strings to the same case and comparing them is significantly more expensive than comparing them with " +
            "<code>strings.EqualFold(s1, s2)</code>: no intermediate strings, and it stops at the first non-matching character."

    private class Match(val left: GoCallExpr, val a: GoExpression, val b: GoExpression, val negated: Boolean)

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val m = match(expression, ctx) ?: return
        val method = (if (m.negated) "!" else "") + "strings.EqualFold"
        ctx.report(expression, "should use $method instead", *GoRewriteFix.offer("Replace with $method", expression, ctx, ::fix))
    }

    private fun match(e: PsiElement, ctx: GoRuleContext): Match? {
        if (e !is GoConditionalExpr || e.eql == null && e.neq == null) return null
        val left = e.left as? GoCallExpr ?: return null
        val right = e.right as? GoCallExpr ?: return null
        val name = GoSimplePsi.calleeName(left) ?: return null
        if (name !in NAMES || GoSimplePsi.calleeName(right) != name) return null
        val a = GoStaticcheckPsi.arguments(left)?.singleOrNull() ?: return null
        val b = GoStaticcheckPsi.arguments(right)?.singleOrNull() ?: return null
        if (!GoExpressionPsi.sameCode(left.expression, right.expression)) return null
        if (GoSimplePsi.callee(left, NAMES, ctx) != "strings.$name" || GoSimplePsi.callee(right, NAMES, ctx) != "strings.$name") return null
        return Match(left, a, b, e.neq != null)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val m = match(element, ctx) ?: return null
        if (GoSimplePsi.commentsOutside(element, listOf(m.a.textRange, m.b.textRange))) return null
        if (m.a.text.contains('\n') || m.b.text.contains('\n')) return null
        val qualifier = GoSimplePsi.qualifier(m.left) ?: return null
        return GoSimplePsi.replaceWith(element, (if (m.negated) "!" else "") + "${qualifier}EqualFold(${m.a.text}, ${m.b.text})")
    }

    private companion object {
        val NAMES = setOf("ToLower", "ToUpper")
    }
}

/** staticcheck SA6006: `io.WriteString(w, string(b))` of a byte slice copies it into a string only to write it; `w.Write(b)` does not. */
class GoWriteStringBytesRule : GoSimpleCallRule() {
    override val id: String get() = "SA6006"
    override val linterAliases: Set<String> get() = emptySet()
    override val title: String get() = "Using io.WriteString to write []byte"
    override val description: String get() =
        "Using <code>io.WriteString</code> to write a slice of bytes, as in <code>io.WriteString(w, string(b))</code>, is both unnecessary and " +
            "inefficient. Converting from <code>[]byte</code> to <code>string</code> has to allocate and copy the data, and we could simply use " +
            "<code>w.Write(b)</code> instead."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        if (match(call, ctx) == null) return
        ctx.report(call, "use io.Writer.Write instead of converting from []byte to string to use io.WriteString",
            *GoRewriteFix.offer("Use Write", call, ctx, ::fix))
    }

    /** (writer, byte slice). */
    private fun match(call: GoCallExpr, ctx: GoRuleContext): Pair<GoExpression, GoExpression>? {
        if (GoSimplePsi.calleeName(call) != "WriteString") return null
        val args = GoStaticcheckPsi.arguments(call) ?: return null
        if (args.size != 2) return null
        val conversion = args[1] as? GoCallExpr ?: return null
        if (GoExpressionPsi.builtinName(conversion.expression, ctx) != "string") return null
        val arg = GoStaticcheckPsi.arguments(conversion)?.singleOrNull() ?: return null
        if (GoSimplePsi.callee(call, WRITE_STRING, ctx) != "io.WriteString") return null
        if (!GoSimplePsi.isStringConvertibleByteSlice(ctx.typeOf(arg), ctx)) return null
        return args[0] to arg
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val (w, b) = match(call, ctx) ?: return null
        // Write takes []byte: a slice of a named byte type converts to string but is not assignable
        val elem = (ctx.typeOf(b).underlying() as? GoSliceType)?.elem
        if (elem !is GoBasicType || elem.kind != GoBasicKind.UINT8) return null
        if (GoSimplePsi.commentsOutside(call, listOf(w.textRange, b.textRange))) return null
        return GoSimplePsi.replaceWith(call, "${GoSimplePsi.operand(w)}.Write(${b.text})")
    }

    private companion object {
        val WRITE_STRING = setOf("WriteString")
    }
}
