package io.github.golangsupport.ide.completion

import com.intellij.openapi.progress.ProgressManager
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Second-level completion: `x.F.M` / `x.F().M()` where `x` is a variable or parameter in scope, `F` a field (promoted ones
 * included) or a method without parameters and with one result of its type, and `M` a field or method of `F`'s type. The lookup
 * string is the whole chain, matched also by `M` alone. Bounded: [MAX_ROOTS] variables, [MAX_FIRST] members of each, [MAX_STEPS]
 * expansions overall, [MAX_CHAINS] chains; types come from the variables' declaration types already computed for the scope.
 */
class GoChainCandidates(private val context: GoCompletionContext) {
    private val members = GoMemberCandidates(context)

    /** A first step `x.F` / `x.F()`: its text, its type, and whether the result is addressable (a field of a variable). */
    private class Step(val text: String, val type: GoType, val addressable: Boolean)

    /**
     * Chains from the variables of [scope]. [accept] decides on each one (the prefix in basic completion, the expected type in
     * smart completion); only accepted chains count towards the cap.
     */
    fun collect(scope: List<GoCandidate>, accept: (GoCandidate) -> Boolean, out: MutableList<GoCandidate>) {
        val roots = scope.asSequence().filter { it.kind in ROOT_KINDS && known(it.valueType) }.take(MAX_ROOTS).toList()
        val seen = HashSet<String>()
        var steps = 0
        var chains = 0
        for (root in roots) {
            for (step in firstSteps(root.name, root.valueType!!)) {
                ProgressManager.checkCanceled()
                if (++steps > MAX_STEPS) return
                for (candidate in secondSteps(step)) {
                    if (!seen.add(candidate.lookupString) || !accept(candidate)) continue
                    out += candidate
                    if (++chains >= MAX_CHAINS) return
                }
            }
        }
    }

    private fun firstSteps(root: String, type: GoType): List<Step> {
        val result = ArrayList<Step>()
        for ((field, _) in members.fields(type)) {
            if (known(field.type)) result += Step("$root.${field.name}", field.type, addressable = true)
        }
        for (m in GoLookup.methodSet(methodSetType(type, addressable = true))) {
            if (result.size >= MAX_FIRST) break
            if (!members.visible(m.isExported, m.pkgPath) || m.signature.params.isNotEmpty() || m.signature.results.size != 1) continue
            val resultType = m.signature.results[0].type
            if (known(resultType)) result += Step("$root.${m.name}()", resultType, addressable = false)
        }
        return result.take(MAX_FIRST)
    }

    private fun secondSteps(step: Step): List<GoCandidate> {
        val type = step.type
        val result = ArrayList<GoCandidate>()
        val names = HashSet<String>()
        for ((field, depth) in members.fields(type)) {
            if (!names.add(field.name)) continue
            result += GoCandidate(
                field.name, GoCandidateKind.FIELD, GoScopeLevel.UNIMPORTED + depth, field.declaration, valueType = field.type,
                typeSupplier = { GoLookupElementFactory.typeText(field.type) }, lookupString = "${step.text}.${field.name}", lookupStrings = listOf(field.name),
            )
        }
        for (m in GoLookup.methodSet(methodSetType(type, step.addressable))) {
            if (!members.visible(m.isExported, m.pkgPath) || !names.add(m.name)) continue
            result += members.methodCandidate(m, 0, GoScopeLevel.UNIMPORTED, lookupString = "${step.text}.${m.name}", lookupStrings = listOf(m.name))
        }
        return result
    }

    /** A variable is addressable, so its pointer method set applies (as in [GoMemberCandidates] for `v.`). */
    private fun methodSetType(type: GoType, addressable: Boolean): GoType =
        if (addressable && type !is GoPointerType && type.underlying() !is GoInterfaceType && type !is GoTypeParamType) GoPointerType(type) else type

    /** Types with members worth a chain: not unknown, not a basic type (no fields, no methods unless named). */
    private fun known(type: GoType?): Boolean = type != null && type !is GoUnknownType && (type !is GoBasicType) &&
        (type is GoNamedType || type is GoPointerType || type.underlying() !is GoBasicType)

    companion object {
        const val MAX_ROOTS = 30
        const val MAX_FIRST = 20
        const val MAX_STEPS = 200
        const val MAX_CHAINS = 50
        private val ROOT_KINDS = setOf(GoCandidateKind.LOCAL, GoCandidateKind.PARAMETER, GoCandidateKind.VARIABLE)
    }
}
