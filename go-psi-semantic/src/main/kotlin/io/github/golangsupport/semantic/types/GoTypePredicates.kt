package io.github.golangsupport.semantic.types

import com.intellij.openapi.util.RecursionManager

/** Type relations from the spec: identity, assignability, comparability, interface satisfaction. */
object GoTypePredicates {

    /** Spec "Type identity". Unknown types are identical to nothing except themselves. */
    fun identical(a: GoType, b: GoType): Boolean {
        if (a === b) return true
        return when (a) {
            is GoUnknownType -> false
            is GoBasicType -> b is GoBasicType && a.kind == b.kind
            is GoArrayType -> b is GoArrayType && a.length == b.length && identical(a.elem, b.elem)
            is GoSliceType -> b is GoSliceType && identical(a.elem, b.elem)
            is GoPointerType -> b is GoPointerType && identical(a.elem, b.elem)
            is GoMapType -> b is GoMapType && identical(a.key, b.key) && identical(a.value, b.value)
            is GoChanType -> b is GoChanType && a.dir == b.dir && identical(a.elem, b.elem)
            is GoTupleType -> b is GoTupleType && a.types.size == b.types.size && a.types.indices.all { identical(a.types[it], b.types[it]) }
            is GoStructType -> b is GoStructType && a.fields.size == b.fields.size && a.fields.indices.all { i ->
                val x = a.fields[i]; val y = b.fields[i]
                x.name == y.name && x.embedded == y.embedded && x.tag == y.tag && identical(x.type, y.type) &&
                    (x.isExported || x.pkgPath == y.pkgPath)
            }
            is GoSignatureType -> b is GoSignatureType && a.variadic == b.variadic && a.typeParams.size == b.typeParams.size &&
                a.params.size == b.params.size && a.results.size == b.results.size &&
                a.params.indices.all { identical(a.params[it].type, b.params[it].type) } &&
                a.results.indices.all { identical(a.results[it].type, b.results[it].type) }
            is GoInterfaceType -> b is GoInterfaceType && (RecursionManager.doPreventingRecursion(a to b, false) {
                val am = a.allMethods; val bm = b.allMethods
                am.size == bm.size && am.all { m -> bm.firstOrNull { it.name == m.name }?.let { identical(m.signature, it.signature) } == true } &&
                    sameTerms(a.typeTerms, b.typeTerms)
            } ?: true)
            is GoNamedType -> b is GoNamedType && a.declaration == b.declaration && a.typeArgs.size == b.typeArgs.size &&
                a.typeArgs.indices.all { identical(a.typeArgs[it], b.typeArgs[it]) }
            is GoTypeParamType -> b is GoTypeParamType && a == b // declaration and rename generation
            is GoUnionType -> b is GoUnionType && sameTerms(a.terms, b.terms)
        }
    }

    /** Identity ignoring struct tags at every level (conversions between struct types, Go 1.8). */
    fun identicalIgnoreTags(a: GoType, b: GoType): Boolean {
        if (a === b) return true
        return when (a) {
            is GoStructType -> b is GoStructType && a.fields.size == b.fields.size && a.fields.indices.all { i ->
                val x = a.fields[i]; val y = b.fields[i]
                x.name == y.name && x.embedded == y.embedded && identicalIgnoreTags(x.type, y.type) && (x.isExported || x.pkgPath == y.pkgPath)
            }
            is GoPointerType -> b is GoPointerType && identicalIgnoreTags(a.elem, b.elem)
            is GoSliceType -> b is GoSliceType && identicalIgnoreTags(a.elem, b.elem)
            is GoArrayType -> b is GoArrayType && a.length == b.length && identicalIgnoreTags(a.elem, b.elem)
            is GoMapType -> b is GoMapType && identicalIgnoreTags(a.key, b.key) && identicalIgnoreTags(a.value, b.value)
            is GoChanType -> b is GoChanType && a.dir == b.dir && identicalIgnoreTags(a.elem, b.elem)
            is GoSignatureType -> b is GoSignatureType && a.variadic == b.variadic && a.params.size == b.params.size && a.results.size == b.results.size &&
                a.params.indices.all { identicalIgnoreTags(a.params[it].type, b.params[it].type) } && a.results.indices.all { identicalIgnoreTags(a.results[it].type, b.results[it].type) }
            // Spec "Conversions": only struct tags are ignored; nested named types must still be
            // identical (callers pass the operands' underlying types at the top level).
            is GoNamedType -> b is GoNamedType && identical(a, b)
            else -> identical(a, b)
        }
    }

    private fun sameTerms(a: List<GoTerm>?, b: List<GoTerm>?): Boolean {
        if (a == null || b == null) return a == b
        if (a.size != b.size) return false
        return a.all { x -> b.any { y -> x.tilde == y.tilde && identical(x.type, y.type) } }
    }

    /** Spec "Assignability" (untyped constants by kind only; no representability/overflow checks). */
    fun assignable(value: GoType, target: GoType): Boolean {
        if (value is GoUnknownType || target is GoUnknownType) return true
        if (identical(value, target)) return true
        val vu = value.underlying()
        val tu = target.underlying()
        if (value is GoBasicType && value.isUntyped) {
            // go/types: an untyped value is assignable to a type parameter when it is assignable to every specific type.
            if (target is GoTypeParamType) return paramAccepts(target) { assignable(value, it) }
            if (value.kind == GoBasicKind.UNTYPED_NIL) {
                return tu is GoPointerType || tu is GoSignatureType || tu is GoSliceType || tu is GoMapType || tu is GoChanType ||
                    tu is GoInterfaceType || tu == GoBasicType.UNSAFE_POINTER
            }
            if (tu is GoInterfaceType) return !tu.hasTypeTerms && tu.allMethods.isEmpty()
            val tb = tu as? GoBasicType ?: return false
            return representableKind(value.kind, tb.kind)
        }
        // Identical underlying types and at least one is not a named type (predeclared types are named; type parameters excluded).
        if (identical(vu, tu) && (!isNamed(value) || !isNamed(target)) && value !is GoTypeParamType && target !is GoTypeParamType) return true
        // Interface satisfaction.
        if (tu is GoInterfaceType && target !is GoTypeParamType && implements(value, tu)) return true
        // Bidirectional channel to directional channel with identical element types.
        if (vu is GoChanType && tu is GoChanType && vu.dir == GoChanDir.BOTH && identical(vu.elem, tu.elem) && (!isNamed(value) || !isNamed(target))) return true
        // V is not a named type and T is a type parameter / V is a type parameter and T is not a named type: every specific type.
        if (target is GoTypeParamType && !isNamed(value)) return paramAccepts(target) { assignable(value, it) }
        if (value is GoTypeParamType && !isNamed(target)) return paramAccepts(value) { assignable(it, target) }
        return false
    }

    /** Spec "named types": predeclared and defined types (and type parameters). */
    fun isNamed(t: GoType): Boolean = t is GoNamedType || t is GoBasicType || t is GoTypeParamType

    /** True when every term of the parameter's constraint satisfies [check]; false without terms. */
    private fun paramAccepts(p: GoTypeParamType, check: (GoType) -> Boolean): Boolean {
        val terms = p.terms ?: return false
        return terms.isNotEmpty() && terms.all { check(it.type) }
    }

    /** Untyped constant kind representable in a typed basic kind (by category only). */
    fun representableKind(untyped: GoBasicKind, typed: GoBasicKind): Boolean = when (untyped) {
        GoBasicKind.UNTYPED_BOOL -> typed.isBoolean
        GoBasicKind.UNTYPED_STRING -> typed.isString
        GoBasicKind.UNTYPED_INT, GoBasicKind.UNTYPED_RUNE -> typed.isNumeric
        GoBasicKind.UNTYPED_FLOAT -> typed.isNumeric // integer targets need an integral value; not checked here
        GoBasicKind.UNTYPED_COMPLEX -> typed.isNumeric
        GoBasicKind.UNTYPED_NIL -> typed == GoBasicKind.UNSAFE_POINTER
        else -> untyped == typed
    }

    /** The default type of an untyped constant kind (spec "Constants"). */
    fun defaultType(type: GoType): GoType {
        val b = type as? GoBasicType ?: return type
        return when (b.kind) {
            GoBasicKind.UNTYPED_BOOL -> GoBasicType.BOOL
            GoBasicKind.UNTYPED_INT -> GoBasicType.INT
            GoBasicKind.UNTYPED_RUNE -> GoBasicType.RUNE
            GoBasicKind.UNTYPED_FLOAT -> GoBasicType.FLOAT64
            GoBasicKind.UNTYPED_COMPLEX -> GoBasicType.COMPLEX128
            GoBasicKind.UNTYPED_STRING -> GoBasicType.STRING
            else -> type
        }
    }

    fun isUntyped(type: GoType): Boolean = type is GoBasicType && type.isUntyped

    /**
     * Spec "Comparison operators": whether `==` is defined on values of this type. Interfaces are
     * comparable (not strictly); a type parameter is comparable when its constraint is
     * `comparable` or every term of its type set is comparable.
     */
    @JvmOverloads
    fun comparable(type: GoType, strict: Boolean = false): Boolean = RecursionManager.doPreventingRecursion(type to strict, false) {
        if (type is GoTypeParamType) {
            val bound = type.bound.underlying() as? GoInterfaceType
            if (bound != null && bound.isComparableConstraint) return@doPreventingRecursion true
            return@doPreventingRecursion paramAccepts(type) { comparable(it, strict) }
        }
        when (val u = type.underlying()) {
            is GoBasicType -> u.kind != GoBasicKind.UNTYPED_NIL && u.kind != GoBasicKind.INVALID
            is GoPointerType, is GoChanType -> true
            is GoInterfaceType -> !strict && !u.isConstraintOnly
            is GoStructType -> u.fields.all { comparable(it.type, strict) }
            is GoArrayType -> comparable(u.elem, strict)
            is GoSliceType, is GoMapType, is GoSignatureType -> false
            else -> true
        }
    } ?: true

    /**
     * Spec "Satisfying a type constraint": [type] satisfies [constraint] when it implements it, or
     * the constraint is `comparable` (possibly with methods) and [type] is comparable.
     */
    fun satisfies(type: GoType, constraint: GoType): Boolean {
        if (type is GoUnknownType) return true
        val iface = constraint.underlying() as? GoInterfaceType ?: return true
        // `implements` already accepts non-strictly comparable types for `comparable`; type terms of an
        // embedded `interface{ comparable; ~int | ~string }` still apply.
        if (iface.isComparableConstraint && !comparable(type)) return false
        return implements(type, iface)
    }

    /**
     * Why [type] does not satisfy [constraint] (go/types wording), or null when it does:
     * `T does not satisfy C (missing method m)`, `(T missing in int | string)`.
     */
    fun satisfactionFailure(type: GoType, constraint: GoType, render: (GoType) -> String): String? {
        if (satisfies(type, constraint)) return null
        val iface = constraint.underlying() as? GoInterfaceType ?: return null
        val tn = render(type)
        val cn = render(constraint)
        val terms = iface.typeTerms
        if (terms != null && type !is GoTypeParamType && terms.none { t -> if (t.tilde) identical(type.underlying(), t.type.underlying()) else identical(type, t.type) }) {
            val listed = terms.joinToString(" | ") { (if (it.tilde) "~" else "") + render(it.type) }
            return if (terms.isEmpty()) "cannot satisfy $cn (empty type set)" else "$tn does not satisfy $cn ($tn missing in $listed)"
        }
        val missing = missingMethods(type, iface).firstOrNull()
        if (missing != null) {
            if (type !is GoPointerType && GoLookup.methodSet(GoPointerType(type)).any { it.name == missing.name }) return "$tn does not satisfy $cn (method ${missing.name} has pointer receiver)"
            return "$tn does not satisfy $cn (missing method ${missing.name})"
        }
        return "$tn does not satisfy $cn"
    }

    /** True when no part of [type] is unknown (safe to report diagnostics about). */
    fun isKnown(type: GoType): Boolean = RecursionManager.doPreventingRecursion(type, false) {
        when (type) {
            is GoUnknownType -> false
            is GoBasicType -> type.kind != GoBasicKind.INVALID
            is GoArrayType -> type.length != null && isKnown(type.elem)
            is GoSliceType -> isKnown(type.elem)
            is GoPointerType -> isKnown(type.elem)
            is GoMapType -> isKnown(type.key) && isKnown(type.value)
            is GoChanType -> isKnown(type.elem)
            is GoTupleType -> type.types.all(::isKnown)
            is GoStructType -> type.fields.all { isKnown(it.type) }
            is GoSignatureType -> type.params.all { isKnown(it.type) } && type.results.all { isKnown(it.type) }
            is GoInterfaceType -> type.methods.all { isKnown(it.signature) } && type.embedded.all(::isKnown)
            // Named types stop the walk (their underlying type is checked shallowly): a full traversal of a
            // package's type graph per call is far too expensive.
            is GoNamedType -> type.typeArgs.all(::isKnown) && type.underlying().let { u -> u !is GoUnknownType && !(u is GoArrayType && u.length == null) }
            is GoTypeParamType -> true
            is GoUnionType -> type.terms.all { isKnown(it.type) }
        }
    } ?: true

    /**
     * Spec "Implementing an interface": [type] implements [iface] when its method set has every
     * method of the interface with an identical signature, and (for constraints) it is in the type set.
     */
    fun implements(type: GoType, iface: GoInterfaceType): Boolean {
        if (type is GoUnknownType) return true
        // A type parameter with an empty type set is in every type set (go/types: vacuous satisfaction).
        if (type is GoTypeParamType && type.terms?.isEmpty() == true) return true
        if (iface.isComparableConstraint && !comparable(type)) return false
        val terms = iface.typeTerms
        if (terms != null) {
            val inSet = terms.any { t ->
                if (t.tilde) identical(type.underlying(), t.type.underlying()) else identical(type, t.type)
            }
            if (!inSet && type !is GoTypeParamType) return false
            if (!inSet && type is GoTypeParamType && !paramAccepts(type) { pt -> terms.any { t -> if (t.tilde) identical(pt.underlying(), t.type.underlying()) else identical(pt, t.type) } }) return false
        }
        val required = iface.allMethods
        if (required.isEmpty()) return true
        val methodSet = GoLookup.methodSet(type)
        for (m in required) {
            // An unknown package path (null) matches: the two sides may come from stub and AST views of one package.
            val found = methodSet.firstOrNull { it.name == m.name && (m.isExported || it.pkgPath == null || m.pkgPath == null || it.pkgPath == m.pkgPath) } ?: return false
            if (!identical(found.signature, m.signature)) return false
        }
        return true
    }

    /** The missing methods of [iface] on [type] (empty when it implements it); for diagnostics. */
    fun missingMethods(type: GoType, iface: GoInterfaceType): List<GoMethod> {
        val methodSet = GoLookup.methodSet(type)
        return iface.allMethods.filter { m -> methodSet.none { it.name == m.name && identical(it.signature, m.signature) } }
    }

    /** Spec "Conversions", loosely: used to decide whether `T(x)` is a conversion that type-checks. */
    fun convertible(value: GoType, target: GoType): Boolean {
        if (assignable(value, target)) return true
        val vu = value.underlying()
        val tu = target.underlying()
        if (identical(vu, tu) || identicalIgnoreTags(vu, tu)) return true
        // Spec: x's type and T are *unnamed* pointer types whose base types have identical underlying types.
        if (value is GoPointerType && target is GoPointerType && identicalIgnoreTags(value.elem.underlying(), target.elem.underlying())) return true
        if (vu is GoBasicType && tu is GoBasicType) {
            // Spec: both integer or floating-point types, or both complex types (untyped constants convert by value).
            if (vu.kind.isNumeric && tu.kind.isNumeric && (vu.isUntyped || vu.kind.isComplex == tu.kind.isComplex)) return true
            if (vu.kind.isInteger && tu.kind.isString) return true
            if (vu.kind.isString && tu.kind.isString) return true
        }
        if (tu is GoBasicType && tu.kind.isString && vu is GoSliceType) {
            val e = vu.elem.underlying() as? GoBasicType
            return e?.kind == GoBasicKind.UINT8 || e?.kind == GoBasicKind.INT32
        }
        if (vu is GoBasicType && vu.kind.isString && tu is GoSliceType) {
            val e = tu.elem.underlying() as? GoBasicType
            return e?.kind == GoBasicKind.UINT8 || e?.kind == GoBasicKind.INT32
        }
        if (vu is GoSliceType && tu is GoArrayType && identical(vu.elem, tu.elem)) return true
        if (vu is GoSliceType && tu is GoPointerType && tu.elem.underlying() is GoArrayType && identical(vu.elem, (tu.elem.underlying() as GoArrayType).elem)) return true
        if (tu == GoBasicType.UNSAFE_POINTER && (vu is GoPointerType || vu == GoBasicType.UINTPTR)) return true
        if (vu == GoBasicType.UNSAFE_POINTER && (tu is GoPointerType || tu == GoBasicType.UINTPTR)) return true
        return value is GoTypeParamType || target is GoTypeParamType
    }
}
