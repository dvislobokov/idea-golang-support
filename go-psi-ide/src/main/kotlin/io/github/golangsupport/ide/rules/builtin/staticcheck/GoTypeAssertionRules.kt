package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeSwitchGuard
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.types
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * staticcheck SA5010: an interface-to-interface assertion that can never succeed: both interfaces have a method of the same name with
 * different signatures (`x.(B)` where `x` is an `A`, `A` has `F() int` and `B` has `F() string`). Type switch cases too.
 */
class GoImpossibleAssertionRule : GoStaticcheckExpressionRule() {
    override val id: String get() = ID
    override val title: String get() = "Impossible type assertion"
    override val description: String get() =
        "No type can have two methods of the same name: asserting an <code>interface{ F() int }</code> to an <code>interface{ F() string }</code> " +
            "always fails. Generic interfaces are left alone."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        for (target in GoAssertionPsi.targets(expression, ctx)) {
            val wrong = GoAssertionPsi.conflicts(target.from, target.to) ?: continue
            if (wrong.isEmpty()) continue
            val details = wrong.joinToString("; ") { (have, want) ->
                "wrong type for ${have.name} method (have ${GoExpressionPsi.typeString(have.signature, ctx)}, want ${GoExpressionPsi.typeString(want.signature, ctx)})"
            }
            val from = GoExpressionPsi.typeString(target.from, ctx)
            val to = GoExpressionPsi.typeString(target.to, ctx)
            ctx.report(target.assertion ?: target.typeNode, "impossible type assertion; $from and $to contradict each other: $details")
        }
    }

    companion object {
        const val ID = "SA5010"
    }
}

/** govet `ifaceassert`: the same check as SA5010 under vet's id and message, reported on the asserted type; quiet while SA5010 runs. */
class GoVetIfaceAssertRule : GoVetExpressionRule() {
    override val id: String get() = "govet:ifaceassert"
    override val title: String get() = "Impossible interface-to-interface type assertion"
    override val description: String get() =
        "govet <code>ifaceassert</code>: <code>x.(T)</code> or a type switch case where no type can implement both interfaces (a method with " +
            "conflicting signatures). Same check as SA5010."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val targets = GoAssertionPsi.targets(expression, ctx)
        if (targets.isEmpty()) return
        var standDown: Boolean? = null
        for (target in targets) {
            val first = GoAssertionPsi.conflicts(target.from, target.to)?.firstOrNull() ?: continue
            if (standDown == null) standDown = GoStaticcheckPsi.enabled(GoImpossibleAssertionRule.ID, ctx)
            if (standDown) return
            val from = GoExpressionPsi.typeString(target.from, ctx)
            val to = GoExpressionPsi.typeString(target.to, ctx)
            ctx.report(target.typeNode, "impossible type assertion: no type can implement both $from and $to (conflicting types for ${first.first.name} method)")
        }
    }
}

internal object GoAssertionPsi {
    /** An assertion of a [from] value to the interface [to]; [assertion] is the `x.(T)` expression, null for a type switch case. */
    class Target(val assertion: GoTypeAssertionExpr?, val typeNode: PsiElement, val from: GoType, val to: GoType)

    /** The assertions [e] makes: `x.(T)`, or every case type when [e] is the `x` of a type switch guard `x.(type)`. */
    fun targets(e: GoExpression, ctx: GoRuleContext): List<Target> {
        if (e is GoTypeAssertionExpr) {
            val from = ctx.typeOf(e.expression)
            if (from.underlying() !is GoInterfaceType) return emptyList()
            return listOf(Target(e, e.type, from, ctx.typeOf(e)))
        }
        val guard = e.parent as? GoTypeSwitchGuard ?: return emptyList()
        if (guard.expression !== e) return emptyList()
        val switch = PsiTreeUtil.getParentOfType(guard, GoTypeSwitchStatement::class.java) ?: return emptyList()
        if (switch.guard !== guard) return emptyList()
        val from = ctx.typeOf(e)
        if (from.underlying() !is GoInterfaceType) return emptyList()
        return switch.typeCaseClauseList.flatMap { clause ->
            clause.types.filter { it.text != "nil" }.map { Target(null, it, from, GoExpressionPsi.typeOf(it)) }
        }
    }

    /**
     * The methods of interface [to] that interface [from] also has under the same name but with a different signature, as
     * (from's, to's) pairs; null when either is not a known interface or generics are involved (staticcheck gives up there too).
     */
    fun conflicts(from: GoType, to: GoType): List<Pair<GoMethod, GoMethod>>? {
        val fi = from.underlying() as? GoInterfaceType ?: return null
        val ti = to.underlying() as? GoInterfaceType ?: return null
        if (from is GoTypeParamType || to is GoTypeParamType || generic(from) || generic(to)) return null
        if (!GoTypePredicates.isKnown(from) || !GoTypePredicates.isKnown(to)) return null
        if (ti.isConstraintOnly || fi.isConstraintOnly) return null
        val have = fi.allMethods
        val out = ArrayList<Pair<GoMethod, GoMethod>>()
        for (want in ti.allMethods) {
            val m = have.firstOrNull { it.name == want.name } ?: continue
            if (!want.isExported && (m.pkgPath == null || want.pkgPath == null || m.pkgPath != want.pkgPath)) continue
            if (!GoTypePredicates.isKnown(m.signature) || !GoTypePredicates.isKnown(want.signature)) return null
            if (mentionsTypeParam(m.signature, 0) || mentionsTypeParam(want.signature, 0)) return null
            if (!GoTypePredicates.identical(m.signature, want.signature)) out += m to want
        }
        return out
    }

    private fun generic(t: GoType): Boolean = t is GoNamedType && (t.typeArgs.isNotEmpty() || t.isGeneric)

    /** Whether [t] mentions a type parameter or an instantiated generic type (named types are not expanded). */
    private fun mentionsTypeParam(t: GoType, depth: Int): Boolean {
        if (depth > 6) return true
        return when (t) {
            is GoTypeParamType -> true
            is GoNamedType -> generic(t)
            is GoPointerType -> mentionsTypeParam(t.elem, depth + 1)
            is GoSliceType -> mentionsTypeParam(t.elem, depth + 1)
            is GoArrayType -> mentionsTypeParam(t.elem, depth + 1)
            is GoChanType -> mentionsTypeParam(t.elem, depth + 1)
            is GoMapType -> mentionsTypeParam(t.key, depth + 1) || mentionsTypeParam(t.value, depth + 1)
            is GoTupleType -> t.types.any { mentionsTypeParam(it, depth + 1) }
            is GoSignatureType -> t.isGeneric || t.params.any { mentionsTypeParam(it.type, depth + 1) } || t.results.any { mentionsTypeParam(it.type, depth + 1) }
            is GoInterfaceType -> t.methods.any { mentionsTypeParam(it.signature, depth + 1) }
            else -> false
        }
    }
}
