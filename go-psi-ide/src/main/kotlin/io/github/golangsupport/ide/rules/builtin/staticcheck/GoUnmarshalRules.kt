package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/** staticcheck SA1014: a non-pointer passed to `json.Unmarshal`, `xml.Unmarshal` or a `json` / `xml` `Decoder.Decode`. */
class GoUnmarshalPointerRule : GoStaticcheckCallRule() {
    override val id: String get() = ID
    override val title: String get() = "Unmarshal into a non-pointer"
    override val description: String get() =
        "<code>json.Unmarshal(data, v)</code>, <code>xml.Unmarshal</code> and <code>Decoder.Decode</code> need a pointer to fill: <code>&amp;v</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        val (index, shown) = TARGETS[callee] ?: return
        val argument = arguments.getOrNull(index) ?: return
        if (!GoUnmarshalPsi.isNonPointer(argument, ctx)) return
        ctx.report(argument, "$shown expects to unmarshal into a pointer, but the provided value is not a pointer", *GoUnmarshalPsi.fixes(argument))
    }

    companion object {
        const val ID = "SA1014"

        /** Callee -> (argument index, name in the message). */
        internal val TARGETS = mapOf(
            "encoding/json.Unmarshal" to (1 to "json.Unmarshal"),
            "encoding/xml.Unmarshal" to (1 to "xml.Unmarshal"),
            "encoding/json.Decoder.Decode" to (0 to "Decode"),
            "encoding/xml.Decoder.Decode" to (0 to "Decode"),
            "encoding/xml.Decoder.DecodeElement" to (0 to "DecodeElement"),
        )
        private val NAMES = setOf("Unmarshal", "Decode", "DecodeElement")
    }
}

/** govet `unmarshal`: the same check as [GoUnmarshalPointerRule] (SA1014) under vet's id, callees and message; quiet where SA1014 reports. */
class GoVetUnmarshalRule : GoStaticcheckCallRule() {
    override val id: String get() = "govet:unmarshal"
    override val linter: String get() = "govet"
    override val title: String get() = "Unmarshal into a non-pointer (vet)"
    override val description: String get() =
        "govet <code>unmarshal</code>: a non-pointer passed to <code>Unmarshal</code> of <code>encoding/json</code>, <code>xml</code>, <code>asn1</code> " +
            "or to <code>Decoder.Decode</code> of <code>json</code>, <code>xml</code>, <code>gob</code>. Same check as SA1014."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        val index = TARGETS[callee] ?: return
        val argument = arguments.getOrNull(index) ?: return
        if (!GoUnmarshalPsi.isNonPointer(argument, ctx)) return
        if (callee in GoUnmarshalPointerRule.TARGETS && GoStaticcheckPsi.enabled(GoUnmarshalPointerRule.ID, ctx)) return
        val name = callee.substringAfterLast('.')
        val message = if (index == 0) "call of $name passes non-pointer" else "call of $name passes non-pointer as second argument"
        ctx.report(argument, message, *GoUnmarshalPsi.fixes(argument))
    }

    private companion object {
        val TARGETS = mapOf(
            "encoding/json.Unmarshal" to 1, "encoding/xml.Unmarshal" to 1, "encoding/asn1.Unmarshal" to 1,
            "encoding/json.Decoder.Decode" to 0, "encoding/xml.Decoder.Decode" to 0, "encoding/gob.Decoder.Decode" to 0,
        )
        val NAMES = setOf("Unmarshal", "Decode")
    }
}

internal object GoUnmarshalPsi {
    /** A value whose type is known and is neither a pointer nor an interface (nor a type parameter); untyped `nil` is not one. */
    fun isNonPointer(argument: GoExpression, ctx: GoRuleContext): Boolean {
        val type = ctx.typeOf(argument)
        if (type === GoUnknownType || type is GoTypeParamType) return false
        if (type is GoBasicType && (type.kind == GoBasicKind.UNTYPED_NIL || type.kind == GoBasicKind.INVALID)) return false
        return when (type.underlying()) {
            is GoPointerType, is GoInterfaceType, GoUnknownType -> false
            else -> true
        }
    }

    /** `&v` for an addressable-looking name. */
    fun fixes(argument: GoExpression): Array<GoReplaceWithTextFix> =
        if (GoLintPsi.unparen(argument) is GoReferenceExpression) arrayOf(GoReplaceWithTextFix("Pass a pointer", "&${argument.text}")) else emptyArray()
}
