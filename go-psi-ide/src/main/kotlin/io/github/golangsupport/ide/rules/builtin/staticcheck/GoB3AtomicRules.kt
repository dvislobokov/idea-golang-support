package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoStructType

/**
 * staticcheck SA1027: `atomic.AddInt64(&s.f, …)` (and the other 64-bit functions of `sync/atomic`) on a field that is not 8-byte aligned
 * on a 32-bit target, where such an access panics. Only when the project builds for a 32-bit GOARCH; `&s.f` may come through a local
 * variable assigned once.
 */
class GoAtomicAlignmentRule : GoStaticcheckCallRule() {
    override val id: String get() = ID
    override val title: String get() = "Misaligned 64-bit atomic access"
    override val description: String get() =
        "On 32-bit platforms (386, arm, mips) 64-bit atomic operations need an 8-byte aligned address, but struct fields are only 4-byte aligned: " +
            "put the 64-bit fields first in the struct. Checked only when the project builds for a 32-bit GOARCH."
    override val calleeNames: Set<String> get() = GoAtomicAlignment.NAMES
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES_FLOW

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (!callee.startsWith("sync/atomic.") || callee.removePrefix("sync/atomic.") !in GoAtomicAlignment.NAMES) return
        val pointer = arguments.firstOrNull() ?: return
        if (!GoB3Psi.is32Bit(ctx)) return
        val origin = GoB3Psi.origin(pointer, ctx)
        if (origin.resultIndex >= 0) return
        val field = GoAtomicAlignment.misalignedField(origin.expression, ctx) ?: return
        ctx.report(call, "address of non 64-bit aligned field $field passed to $callee")
    }

    companion object {
        const val ID = "SA1027"
    }
}

/** govet `atomicalign`: the same check as [GoAtomicAlignmentRule] (SA1027) under vet's id and message, on `&x.f` written in the call; quiet while SA1027 runs. */
class GoVetAtomicAlignRule : GoStaticcheckCallRule() {
    override val id: String get() = "govet:atomicalign"
    override val linter: String get() = "govet"
    override val title: String get() = "Misaligned 64-bit atomic access (vet)"
    override val description: String get() =
        "govet <code>atomicalign</code>: <code>&amp;x.f</code> passed to a 64-bit <code>sync/atomic</code> function where <code>f</code> is not " +
            "8-byte aligned on a 32-bit GOARCH. Same check as SA1027."
    override val enabledByDefault: Boolean get() = false
    override val enabledWithLinter: Boolean get() = false
    override val calleeNames: Set<String> get() = GoAtomicAlignment.NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (!callee.startsWith("sync/atomic.")) return
        val name = callee.removePrefix("sync/atomic.")
        if (name !in GoAtomicAlignment.NAMES) return
        val pointer = arguments.firstOrNull() ?: return
        if (!GoB3Psi.is32Bit(ctx)) return
        val field = GoAtomicAlignment.misalignedField(pointer, ctx) ?: return
        if (GoStaticcheckPsi.enabled(GoAtomicAlignmentRule.ID, ctx)) return
        ctx.report(pointer, "address of non 64-bit aligned field .$field passed to atomic.$name")
    }
}

internal object GoAtomicAlignment {
    val NAMES = setOf("AddInt64", "AddUint64", "CompareAndSwapInt64", "CompareAndSwapUint64", "LoadInt64", "LoadUint64", "StoreInt64", "StoreUint64",
        "SwapInt64", "SwapUint64")

    /** The name of the field of `&x.f` ([e]) when its offset in the struct of `x` is not a multiple of 8 on 32 bits; null otherwise. */
    fun misalignedField(e: GoExpression, ctx: GoRuleContext): String? {
        val unary = GoLintPsi.unparen(e) as? GoUnaryExpr ?: return null
        if (unary.and == null) return null
        val selector = GoLintPsi.unparen(unary.expression) as? GoReferenceExpression ?: return null
        val x = selector.expression ?: return null
        val name = selector.identifier?.text ?: return null
        val xType = ctx.typeOf(x).let { if (it is GoPointerType) it.elem else it }
        val struct = xType.underlying() as? GoStructType ?: return null
        val index = struct.fields.indexOfFirst { it.name == name }
        if (index < 0) return null
        val offsets = GoB3Psi.Sizes32.offsetsof(GoStructType(struct.fields.subList(0, index + 1))) ?: return null
        return if (offsets[index] % 8 != 0L) name else null
    }
}
