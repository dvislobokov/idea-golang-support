package io.github.golangsupport.semantic.infer

import io.github.golangsupport.semantic.types.*
import io.github.golangsupport.semantic.types.GoTypePredicates.defaultType
import io.github.golangsupport.semantic.types.GoTypePredicates.identical
import io.github.golangsupport.semantic.types.GoTypePredicates.isUntyped
import org.jetbrains.annotations.ApiStatus

/**
 * Type unification (`go/types` unify.go): binds the type parameters in [params] so that two
 * types become identical. Inexact mode (the default, go/types "assign" mode) lets a defined type
 * unify with its underlying type when the other side is not defined, ignores channel direction
 * when one side is bidirectional, and unifies an interface with a concrete type through its
 * methods (interface inference). Untyped constants never bind a parameter here; they are
 * defaulted by [GoInference] afterwards.
 */
@ApiStatus.Internal
class GoUnifier(private val params: Set<GoTypeParamType>) {
    val bindings = LinkedHashMap<GoTypeParamType, GoType>()

    fun isParam(t: GoType): Boolean = t is GoTypeParamType && t in params

    fun unify(x: GoType, y: GoType, exact: Boolean = false): Boolean = nify(x, y, exact, 0)

    private fun nify(x0: GoType, y0: GoType, exact: Boolean, depth: Int): Boolean {
        if (depth > 64) return false
        val x = x0
        val y = y0
        if (x is GoUnknownType || y is GoUnknownType) return true
        if (isParam(x)) bindings[x as GoTypeParamType]?.let { return nify(it, y, exact, depth + 1) }
        if (isParam(y)) bindings[y as GoTypeParamType]?.let { return nify(x, it, exact, depth + 1) }
        val px = isParam(x)
        val py = isParam(y)
        if (px && py) {
            if (x == y) return true
            bindings[x as GoTypeParamType] = y
            return true
        }
        if (px) return bind(x as GoTypeParamType, y)
        if (py) return bind(y as GoTypeParamType, x)
        if (identical(x, y)) return true
        // A foreign type parameter (of an enclosing generic function) unifies through its core type.
        if (!exact && y is GoTypeParamType && x !is GoTypeParamType) y.coreType?.let { return nify(x, it, exact, depth + 1) }
        if (!exact && x is GoTypeParamType && y !is GoTypeParamType) x.coreType?.let { return nify(it, y, exact, depth + 1) }
        if (x is GoNamedType && y is GoNamedType) {
            if (x.declaration == y.declaration) {
                if (x.typeArgs.size != y.typeArgs.size) return false
                // Top-level interfaces unify through their methods (go/types interface inference), not their type arguments.
                if (!exact && depth == 0 && x.underlying() is GoInterfaceType) {
                    val xi = x.underlying() as GoInterfaceType; val yi = y.underlying() as? GoInterfaceType ?: return false
                    return xi.allMethods.all { m -> yi.allMethods.firstOrNull { it.name == m.name }?.let { nify(m.signature, it.signature, false, depth + 1) } ?: false }
                }
                return x.typeArgs.indices.all { nify(x.typeArgs[it], y.typeArgs[it], true, depth + 1) }
            }
            if (exact) return false
            // Interface inference: a defined interface type against a defined concrete type.
            val xu = x.underlying()
            val yu = y.underlying()
            if (xu is GoInterfaceType && yu !is GoInterfaceType) return unifyInterface(xu, y, depth)
            if (yu is GoInterfaceType && xu !is GoInterfaceType) return unifyInterface(yu, x, depth)
            return false
        }
        if (!exact) {
            if (x is GoNamedType) return nify(x.underlying(), y, exact, depth + 1)
            if (y is GoNamedType) return nify(x, y.underlying(), exact, depth + 1)
        } else {
            if (x is GoNamedType || y is GoNamedType) return false
        }
        return when (x) {
            is GoBasicType -> y is GoBasicType && x.kind == y.kind
            is GoArrayType -> y is GoArrayType && (x.length == null || y.length == null || x.length == y.length) && nify(x.elem, y.elem, exact, depth + 1)
            is GoSliceType -> y is GoSliceType && nify(x.elem, y.elem, exact, depth + 1)
            is GoPointerType -> y is GoPointerType && nify(x.elem, y.elem, exact, depth + 1)
            is GoMapType -> y is GoMapType && nify(x.key, y.key, exact, depth + 1) && nify(x.value, y.value, exact, depth + 1)
            is GoChanType -> y is GoChanType && (!exact || x.dir == y.dir) && nify(x.elem, y.elem, exact, depth + 1)
            is GoTupleType -> y is GoTupleType && x.types.size == y.types.size && x.types.indices.all { nify(x.types[it], y.types[it], exact, depth + 1) }
            is GoSignatureType -> y is GoSignatureType && x.variadic == y.variadic && x.params.size == y.params.size && x.results.size == y.results.size &&
                x.params.indices.all { nify(x.params[it].type, y.params[it].type, exact, depth + 1) } &&
                x.results.indices.all { nify(x.results[it].type, y.results[it].type, exact, depth + 1) }
            is GoStructType -> y is GoStructType && x.fields.size == y.fields.size && x.fields.indices.all { i ->
                val a = x.fields[i]; val b = y.fields[i]
                a.name == b.name && a.embedded == b.embedded && nify(a.type, b.type, exact, depth + 1)
            }
            is GoInterfaceType -> when {
                y is GoInterfaceType -> {
                    val xm = x.allMethods; val ym = y.allMethods
                    if (exact && xm.size != ym.size) return false
                    xm.all { m -> ym.firstOrNull { it.name == m.name }?.let { nify(m.signature, it.signature, exact, depth + 1) } ?: !exact }
                }
                !exact -> unifyInterface(x, y, depth)
                else -> false
            }
            is GoTypeParamType -> y is GoTypeParamType && x == y
            is GoUnionType, is GoUnknownType, is GoNamedType -> false
        }
    }

    /** Interface inference: every method of [iface] must exist on [type] with a unifiable signature. */
    private fun unifyInterface(iface: GoInterfaceType, type: GoType, depth: Int): Boolean {
        val methods = iface.allMethods
        if (methods.isEmpty()) return true
        val set = GoLookup.methodSet(if (type is GoPointerType) type else type)
        val setWithPointer = if (type is GoPointerType) set else GoLookup.methodSet(GoPointerType(type))
        for (m in methods) {
            val found = set.firstOrNull { it.name == m.name } ?: setWithPointer.firstOrNull { it.name == m.name } ?: return false
            if (!nify(m.signature, found.signature, false, depth + 1)) return false
        }
        return true
    }

    private fun bind(p: GoTypeParamType, t: GoType): Boolean {
        if (isUntyped(t)) return true // defaulted later
        if (t === p) return true
        bindings[p] = t
        return true
    }
}

/**
 * Function argument type inference (`go/types` infer.go): typed arguments first, then constraint
 * type inference through core types and method requirements, then untyped constant defaulting,
 * then substitution to a fixed point with cycle detection.
 */
@ApiStatus.Internal
object GoInference {

    /** An argument of a call: its type, whether it is an untyped constant, and the generic signature when it is a generic function value. */
    class Arg(val type: GoType, val isUntypedConstant: Boolean = isUntyped(type), val genericSignature: GoSignatureType? = (type as? GoSignatureType)?.takeIf { it.isGeneric })

    /**
     * Infers the type arguments of [sig] from [args] (and [explicit] leading type arguments).
     * [spread] marks `f(xs...)`. Returns the substitution for every parameter that could be
     * inferred; the caller substitutes and keeps the rest unbound.
     */
    fun infer(sig: GoSignatureType, args: List<Arg>, explicit: List<GoType> = emptyList(), spread: Boolean = false): GoSubstitution {
        val tparams = sig.typeParams
        if (tparams.isEmpty()) return GoSubstitution.EMPTY
        // go/types renameTParams: a generic function calling itself (or a sibling sharing the
        // declarations) passes its own type parameters; infer on fresh copies and map back.
        val own = tparams.toSet()
        if (args.any { containsParams(it.type, own) } || explicit.any { containsParams(it, own) }) {
            val copies = GoTypeParamType.renamed(tparams)
            val toCopies = GoSubstitution.of(tparams, copies)
            val renamedSig = GoSignatureType(
                sig.params.map { it.withType(it.type.substitute(toCopies)) },
                sig.results.map { it.withType(it.type.substitute(toCopies)) },
                sig.variadic, copies, sig.receiver, sig.partialSubst,
            )
            val inferred = inferNoRename(renamedSig, args, explicit, spread)
            val copySet = copies.toSet()
            val back = LinkedHashMap<GoTypeParamType, GoType>()
            for ((orig, copy) in tparams.zip(copies)) {
                val t = inferred[copy] ?: continue
                if (!containsParams(t, copySet)) back[orig] = t
            }
            return GoSubstitution(back)
        }
        return inferNoRename(sig, args, explicit, spread)
    }

    private fun inferNoRename(sig: GoSignatureType, args: List<Arg>, explicit: List<GoType>, spread: Boolean): GoSubstitution {
        val tparams = sig.typeParams
        val partial = sig.partialSubst
        val all = LinkedHashSet<GoTypeParamType>(tparams)
        val genericArgs = args.mapNotNull { it.genericSignature }
        genericArgs.forEach { all += it.typeParams }
        val u = GoUnifier(all)
        for ((i, t) in explicit.withIndex()) tparams.getOrNull(i)?.let { if (t !is GoUnknownType) u.bindings[it] = t }

        // 1. Typed arguments.
        val untypedByParam = HashMap<GoTypeParamType, MutableList<GoBasicKind>>()
        val expanded = expandArgs(args)
        for ((i, a) in expanded.withIndex()) {
            val p = paramTypeAt(sig, i, expanded.size, spread) ?: break
            if (a.isUntypedConstant) {
                val k = (a.type as? GoBasicType)?.kind ?: continue
                if (p is GoTypeParamType && p in all) untypedByParam.getOrPut(p) { ArrayList() } += k
                continue
            }
            if (a.type is GoUnknownType) continue
            u.unify(p, a.type)
        }

        // 2. Constraint type inference (core types and methods), to a fixed point.
        var rounds = 0
        var changed = true
        while (changed && rounds++ < 16) {
            changed = false
            for (tp in all + partial.map.keys) {
                val bound = resolved(u, tp) ?: partial[tp]
                val core = tp.coreType?.substitute(partial)
                if (bound != null) {
                    // A foreign type parameter as the bound (`Grow[S](nil, n)` inside a function with its own `S ~[]E`) unifies through its core type.
                    if (core != null && !u.isParam(bound) && (bound is GoTypeParamType || bound.underlying() !is GoInterfaceType || core is GoInterfaceType)) {
                        val before = u.bindings.size
                        u.unify(core, bound)
                        if (u.bindings.size != before) changed = true
                    }
                    val iface = tp.bound.underlying() as? GoInterfaceType
                    if (iface != null && iface.allMethods.isNotEmpty() && !u.isParam(bound)) {
                        val set = GoLookup.methodSet(bound)
                        for (m in iface.allMethods) {
                            val found = set.firstOrNull { it.name == m.name } ?: continue
                            val before = u.bindings.size
                            u.unify(m.signature, found.signature)
                            if (u.bindings.size != before) changed = true
                        }
                    }
                } else {
                    val single = tp.singleExactTerm?.substitute(partial)
                    if (single != null && !containsParams(single, setOf(tp))) {
                        u.bindings[tp] = single
                        changed = true
                    } else if (core != null && tp in untypedByParam && !containsParams(core, setOf(tp))) {
                        // `[T ~float64](x T)` called with `1`: the untyped constant is converted to the core type.
                        u.bindings[tp] = core
                        changed = true
                    }
                }
            }
        }

        // 3. Untyped constants: the largest kind among the arguments bound only to constants, defaulted.
        for ((tp, kinds) in untypedByParam) {
            if (resolved(u, tp) != null) continue
            val max = kinds.maxBy { untypedRank(it) }
            u.bindings[tp] = defaultType(GoBasicType.of(max))
        }

        // 4. Substitute bindings into each other to a fixed point; parameters still containing
        //    themselves (cycles) are dropped.
        val result = LinkedHashMap<GoTypeParamType, GoType>()
        for (tp in tparams) {
            var t = resolved(u, tp) ?: continue
            var n = 0
            while (n++ < 16 && containsParams(t, all)) {
                val next = t.substitute(GoSubstitution(u.bindings.filterKeys { it != tp }))
                if (next == t) break
                t = next
            }
            t = t.substitute(partial)
            if (!containsParams(t, setOf(tp))) result[tp] = t
        }
        return GoSubstitution(result)
    }

    /** Follows parameter-to-parameter bindings. */
    private fun resolved(u: GoUnifier, tp: GoTypeParamType): GoType? {
        var t = u.bindings[tp] ?: return null
        var n = 0
        while (t is GoTypeParamType && u.isParam(t) && n++ < 16) t = u.bindings[t] ?: return t
        return t
    }

    private fun untypedRank(k: GoBasicKind): Int = when (k) {
        GoBasicKind.UNTYPED_INT -> 1; GoBasicKind.UNTYPED_RUNE -> 2; GoBasicKind.UNTYPED_FLOAT -> 3; GoBasicKind.UNTYPED_COMPLEX -> 4
        else -> 0
    }

    /** A single multi-value argument `f(g())` is spread over the parameters. */
    private fun expandArgs(args: List<Arg>): List<Arg> {
        if (args.size == 1) {
            val t = args[0].type
            if (t is GoTupleType) return t.types.map { Arg(it) }
        }
        return args
    }

    /** The declared parameter type for argument [i] (`...T` unpacked unless the call spreads a slice). */
    fun paramTypeAt(sig: GoSignatureType, i: Int, argCount: Int, spread: Boolean): GoType? {
        val last = sig.params.lastIndex
        if (sig.variadic && i >= last) {
            val slice = sig.params.getOrNull(last)?.type ?: return null
            return if (spread) slice else (slice.underlying() as? GoSliceType)?.elem ?: slice
        }
        return sig.params.getOrNull(i)?.type
    }

    fun containsParams(t: GoType, params: Set<GoTypeParamType>): Boolean = when (t) {
        is GoTypeParamType -> t in params
        is GoArrayType -> containsParams(t.elem, params)
        is GoSliceType -> containsParams(t.elem, params)
        is GoPointerType -> containsParams(t.elem, params)
        is GoMapType -> containsParams(t.key, params) || containsParams(t.value, params)
        is GoChanType -> containsParams(t.elem, params)
        is GoTupleType -> t.types.any { containsParams(it, params) }
        is GoSignatureType -> t.params.any { containsParams(it.type, params) } || t.results.any { containsParams(it.type, params) }
        is GoStructType -> t.fields.any { containsParams(it.type, params) }
        is GoInterfaceType -> t.methods.any { containsParams(it.signature, params) } || t.embedded.any { containsParams(it, params) }
        is GoNamedType -> t.typeArgs.any { containsParams(it, params) }
        is GoUnionType -> t.terms.any { containsParams(it.type, params) }
        is GoBasicType, is GoUnknownType -> false
    }
}
