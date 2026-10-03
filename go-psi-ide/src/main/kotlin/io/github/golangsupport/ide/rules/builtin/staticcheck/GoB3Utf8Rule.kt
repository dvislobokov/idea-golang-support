package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.semantic.types.GoConstant
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * staticcheck SA1011: a constant cutset / character set of `strings.IndexAny`, `LastIndexAny`, `ContainsAny`, `Trim`, `TrimLeft`,
 * `TrimRight` that is not valid UTF-8 (`"\xff"`): these functions work on runes, the byte never matches. The bytes of the constant are
 * evaluated from the literals (string constants of the semantic layer are Java strings and lose `\x` escapes).
 */
class GoInvalidUtf8CutsetRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1011"
    override val title: String get() = "Invalid UTF-8 passed to a strings function"
    override val description: String get() =
        "<code>strings.Trim</code>, <code>TrimLeft</code>, <code>TrimRight</code>, <code>IndexAny</code>, <code>LastIndexAny</code> and " +
            "<code>ContainsAny</code> treat their second argument as a set of runes: a byte like <code>\\xff</code> that is not valid UTF-8 never matches."
    override val calleeNames: Set<String> get() = NAMES
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES_FLOW

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee !in CALLEES) return
        val argument = arguments.getOrNull(1) ?: return
        val source = if (ctx.semantic.constantValue(argument) is GoConstant.Str) argument
        else GoB3Psi.origin(argument, ctx).takeIf { it.resultIndex < 0 }?.expression?.takeIf { ctx.semantic.constantValue(it) is GoConstant.Str } ?: return
        val bytes = bytesOf(source, ctx, 0) ?: return
        if (isValidUtf8(bytes)) return
        ctx.report(argument, "argument is not a valid UTF-8 encoded string")
    }

    private fun bytesOf(e: GoExpression?, ctx: GoRuleContext, depth: Int): ByteArray? {
        if (depth > 8) return null
        return when (val x = GoLintPsi.unparen(e)) {
            is GoStringLiteral -> literalBytes(x)
            is GoAddExpr -> {
                if (x.add == null) return null
                val l = bytesOf(x.left, ctx, depth + 1) ?: return null
                val r = bytesOf(x.right, ctx, depth + 1) ?: return null
                l + r
            }
            is GoReferenceExpression -> {
                val def = ctx.resolve(x).singleOrNull() as? GoConstDefinition ?: return null
                val spec = def.parent as? GoConstSpec ?: return null
                val i = spec.constDefinitionList.indexOf(def)
                bytesOf(spec.expressionList.getOrNull(i), ctx, depth + 1)
            }
            else -> null
        }
    }

    companion object {
        private val CALLEES = setOf("strings.IndexAny", "strings.LastIndexAny", "strings.ContainsAny", "strings.Trim", "strings.TrimLeft", "strings.TrimRight")
        private val NAMES = setOf("IndexAny", "LastIndexAny", "ContainsAny", "Trim", "TrimLeft", "TrimRight")

        fun isValidUtf8(bytes: ByteArray): Boolean = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }

        /** The bytes of a Go string literal: `\x` and octal escapes are single bytes, everything else UTF-8. Null for a malformed literal. */
        fun literalBytes(literal: GoStringLiteral): ByteArray? {
            val text = literal.text
            if (text.length < 2) return null
            if (literal.rawString != null) return text.substring(1, text.length - 1).replace("\r", "").toByteArray(Charsets.UTF_8)
            val body = text.substring(1, text.length - 1)
            val out = ByteArrayOutputStream()
            var i = 0
            fun utf8(cp: Int) = out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            while (i < body.length) {
                val c = body.codePointAt(i)
                if (c != '\\'.code) {
                    utf8(c)
                    i += Character.charCount(c)
                    continue
                }
                if (i + 1 >= body.length) return null
                val e = body[i + 1]
                i += 2
                when (e) {
                    'a' -> out.write(7); 'b' -> out.write(8); 'f' -> out.write(12); 'n' -> out.write(10); 'r' -> out.write(13); 't' -> out.write(9)
                    'v' -> out.write(11); '\\' -> out.write('\\'.code); '\'' -> out.write('\''.code); '"' -> out.write('"'.code)
                    'x' -> { out.write(body.substring(i, minOf(i + 2, body.length)).toIntOrNull(16) ?: return null); i += 2 }
                    'u' -> { utf8(body.substring(i, minOf(i + 4, body.length)).toIntOrNull(16)?.takeIf { it <= 0x10FFFF } ?: return null); i += 4 }
                    'U' -> { utf8(body.substring(i, minOf(i + 8, body.length)).toIntOrNull(16)?.takeIf { it <= 0x10FFFF } ?: return null); i += 8 }
                    in '0'..'7' -> { out.write(body.substring(i - 1, minOf(i + 2, body.length)).toIntOrNull(8)?.takeIf { it <= 255 } ?: return null); i += 2 }
                    else -> return null
                }
            }
            return out.toByteArray()
        }
    }
}
