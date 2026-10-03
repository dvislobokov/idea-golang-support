package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/** staticcheck SA1028: `sort.Slice` (`SliceStable`, `SliceIsSorted`) called on something that is not a slice: it panics. */
class GoSortSliceRule : GoStaticcheckCallRule() {
    override val id: String get() = ID
    override val title: String get() = "sort.Slice on a non-slice"
    override val description: String get() =
        "<code>sort.Slice</code>, <code>SliceStable</code> and <code>SliceIsSorted</code> take a slice (as <code>any</code>) and panic on anything else: " +
            "an array (slice it: <code>a[:]</code>), a pointer to a slice, a string, <code>nil</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee !in CALLEES) return
        val argument = arguments.firstOrNull() ?: return
        val type = ctx.typeOf(argument)
        if (type is GoBasicType && type.kind == GoBasicKind.UNTYPED_NIL) {
            ctx.report(argument, "cannot call $callee on nil literal")
            return
        }
        val t = GoB3Psi.defaultType(type)
        if (!GoB3Psi.isKnown(t)) return
        val u = t.underlying()
        if (u is GoInterfaceType || u is GoSliceType) return
        ctx.report(argument, "$callee must only be called on slices, was called on ${GoB3Psi.typeString(u)}")
    }

    companion object {
        const val ID = "SA1028"
        internal val CALLEES = setOf("sort.Slice", "sort.SliceIsSorted", "sort.SliceStable")
        private val NAMES = setOf("Slice", "SliceIsSorted", "SliceStable")
    }
}

/**
 * govet `sortslice`: the same check as [GoSortSliceRule] (SA1028) under vet's id and message, with vet's fixes (slice the array, dereference
 * the pointer, call the function); quiet while SA1028 runs.
 */
class GoVetSortSliceRule : GoStaticcheckCallRule() {
    override val id: String get() = "govet:sortslice"
    override val linter: String get() = "govet"
    override val title: String get() = "sort.Slice on a non-slice (vet)"
    override val description: String get() = "govet <code>sortslice</code>: the argument of <code>sort.Slice</code> is not a slice. Same check as SA1028."
    override val enabledByDefault: Boolean get() = false
    override val enabledWithLinter: Boolean get() = false
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee !in GoSortSliceRule.CALLEES) return
        val argument = arguments.firstOrNull() ?: return
        val type = GoB3Psi.defaultType(ctx.typeOf(argument))
        if (type === GoUnknownType || type is GoTypeParamType) return
        val u = type.underlying()
        if (u === GoUnknownType || u is GoSliceType || u is GoInterfaceType) return
        if (GoStaticcheckPsi.enabled(GoSortSliceRule.ID, ctx)) return
        val text = argument.text
        val fixes = when {
            u is GoArrayType -> arrayOf(GoReplaceElementFix(argument, "Get a slice of the full array", "$text[:]"))
            u is GoPointerType && u.elem.underlying() is GoSliceType -> arrayOf(GoReplaceElementFix(argument, "Dereference the pointer to the slice", "*$text"))
            u is GoSignatureType && u.params.isEmpty() && u.results.size == 1 && u.results[0].type.underlying() is GoSliceType ->
                arrayOf(GoReplaceElementFix(argument, "Call the function", "$text()"))
            else -> emptyArray()
        }
        ctx.report(call, "$callee's argument must be a slice; is called with ${GoB3Psi.typeString(type)}", *fixes)
    }

    private companion object {
        val NAMES = setOf("Slice", "SliceIsSorted", "SliceStable")
    }
}
