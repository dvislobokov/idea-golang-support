package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoExpressionRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.semantic.psi.GoPsiUtil.indices
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice

/**
 * staticcheck SA1008: `h["content-type"]` on an `http.Header`: the map holds canonical keys (`Content-Type`), so the lookup misses.
 * Writes (`h["x"] = v`) are left alone, as staticcheck does: the whole assignment is skipped.
 */
class GoHttpHeaderKeyRule : GoExpressionRule() {
    override val id: String get() = "SA1008"
    override val linter: String get() = "staticcheck"
    override val title: String get() = "Non-canonical key in http.Header map"
    override val description: String get() =
        "<code>http.Header</code> stores keys in canonical form (<code>Content-Type</code>); indexing it directly with <code>\"content-type\"</code> " +
            "misses. Use the canonical key, <code>Header.Get</code>, or <code>http.CanonicalHeaderKey</code>."
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoIndexOrSliceExpr || expression.isSlice) return
        val index = expression.indices.singleOrNull() as? GoExpression ?: return
        val x = expression.expression ?: return
        val key = GoStaticcheckPsi.stringConstant(index, ctx) ?: return
        if (!GoAnalysisPsi.isNamed(ctx.typeOf(x), "net/http", "Header")) return
        val canonical = canonicalHeaderKey(key)
        if (key == canonical || isInHeaderWrite(expression, ctx)) return
        val fixes = when (val i = GoLintPsi.unparen(index)) {
            is GoStringLiteral -> arrayOf(GoReplaceElementFix(i, "Canonicalize header key", GoStaticcheckPsi.quote(canonical)))
            is GoReferenceExpression -> if (i.expression == null) httpQualifier(ctx.file)?.let { q ->
                arrayOf(GoReplaceElementFix(i, "Wrap in http.CanonicalHeaderKey", "${q}CanonicalHeaderKey(${i.text})"))
            } ?: emptyArray() else emptyArray()
            else -> emptyArray()
        }
        ctx.report(expression, "keys in http.Header are canonicalized, ${GoStaticcheckPsi.quote(key)} is not canonical; fix the constant or use http.CanonicalHeaderKey",
            *fixes)
    }

    /** Inside an assignment that writes an index of an `http.Header` (staticcheck does not look into such assignments at all). */
    private fun isInHeaderWrite(expression: GoExpression, ctx: GoRuleContext): Boolean {
        var e = expression.parent
        while (e != null && e !is PsiFile) {
            if (e is GoAssignmentStatement) {
                val targets = e.leftHandExprList?.expressionList ?: emptyList()
                if (targets.any { t -> t is GoIndexOrSliceExpr && t.expression?.let { GoAnalysisPsi.isNamed(ctx.typeOf(it), "net/http", "Header") } == true }) return true
            }
            e = e.parent
        }
        return false
    }

    /** `http.` (or the import alias) when the file imports `net/http`; null otherwise. */
    private fun httpQualifier(file: GoFile): String? {
        val spec = file.imports.firstOrNull { it.path == "net/http" && it.alias != "_" } ?: return null
        return when (val alias = spec.alias) {
            null -> "http."
            "." -> ""
            else -> "$alias."
        }
    }

    companion object {
        /** `net/textproto.CanonicalMIMEHeaderKey`: a key with a byte outside the token set is returned unchanged. */
        fun canonicalHeaderKey(s: String): String {
            if (s.any { !isTokenChar(it) }) return s
            val sb = StringBuilder(s.length)
            var upper = true
            for (c in s) {
                sb.append(if (upper && c in 'a'..'z') c - 32 else if (!upper && c in 'A'..'Z') c + 32 else c)
                upper = c == '-'
            }
            return sb.toString()
        }

        private fun isTokenChar(c: Char): Boolean = c.code < 0x80 && (c.isLetterOrDigit() || c in "!#$%&'*+-.^_`|~")
    }
}
