package io.github.golangsupport.semantic.types

import com.intellij.openapi.util.RecursionManager
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * The semantic type model: immutable Kotlin objects modelled on `go/types`. Types never throw;
 * anything that cannot be computed is [GoUnknownType]. PSI is referenced only by declaration
 * elements of named types, type parameters, fields and methods (for navigation).
 */
sealed class GoType {
    /** The underlying type (`go/types.Type.Underlying`); itself for non-named types. */
    open fun underlying(): GoType = this

    /** Applies a type-parameter substitution; returns `this` when nothing changes. */
    open fun substitute(subst: GoSubstitution): GoType = this

    override fun toString(): String = GoTypeRenderer.render(this)
}

/** `go/types.BasicKind`, including the untyped kinds. */
enum class GoBasicKind(val typeName: String, val isUntyped: Boolean = false) {
    BOOL("bool"), INT("int"), INT8("int8"), INT16("int16"), INT32("int32"), INT64("int64"),
    UINT("uint"), UINT8("uint8"), UINT16("uint16"), UINT32("uint32"), UINT64("uint64"), UINTPTR("uintptr"),
    FLOAT32("float32"), FLOAT64("float64"), COMPLEX64("complex64"), COMPLEX128("complex128"),
    STRING("string"), UNSAFE_POINTER("unsafe.Pointer"),
    UNTYPED_BOOL("untyped bool", true), UNTYPED_INT("untyped int", true), UNTYPED_RUNE("untyped rune", true),
    UNTYPED_FLOAT("untyped float", true), UNTYPED_COMPLEX("untyped complex", true),
    UNTYPED_STRING("untyped string", true), UNTYPED_NIL("untyped nil", true),
    INVALID("invalid type");

    val isBoolean: Boolean get() = this == BOOL || this == UNTYPED_BOOL
    val isString: Boolean get() = this == STRING || this == UNTYPED_STRING
    val isInteger: Boolean get() = this in INT..UINTPTR || this == UNTYPED_INT || this == UNTYPED_RUNE
    val isUnsigned: Boolean get() = this in UINT..UINTPTR
    val isFloat: Boolean get() = this == FLOAT32 || this == FLOAT64 || this == UNTYPED_FLOAT
    val isComplex: Boolean get() = this == COMPLEX64 || this == COMPLEX128 || this == UNTYPED_COMPLEX
    val isNumeric: Boolean get() = isInteger || isFloat || isComplex
    val isOrdered: Boolean get() = isInteger || isFloat || isString
    val isConstType: Boolean get() = isBoolean || isNumeric || isString
}

class GoBasicType private constructor(val kind: GoBasicKind, val name: String) : GoType() {
    val isUntyped: Boolean get() = kind.isUntyped
    override fun equals(other: Any?): Boolean = other is GoBasicType && other.kind == kind
    override fun hashCode(): Int = kind.hashCode()

    companion object {
        private val byKind = GoBasicKind.entries.associateWith { GoBasicType(it, it.typeName) }
        fun of(kind: GoBasicKind): GoBasicType = byKind.getValue(kind)

        val BOOL = of(GoBasicKind.BOOL); val INT = of(GoBasicKind.INT); val INT8 = of(GoBasicKind.INT8)
        val INT16 = of(GoBasicKind.INT16); val INT32 = of(GoBasicKind.INT32); val INT64 = of(GoBasicKind.INT64)
        val UINT = of(GoBasicKind.UINT); val UINT8 = of(GoBasicKind.UINT8); val UINT16 = of(GoBasicKind.UINT16)
        val UINT32 = of(GoBasicKind.UINT32); val UINT64 = of(GoBasicKind.UINT64); val UINTPTR = of(GoBasicKind.UINTPTR)
        val FLOAT32 = of(GoBasicKind.FLOAT32); val FLOAT64 = of(GoBasicKind.FLOAT64)
        val COMPLEX64 = of(GoBasicKind.COMPLEX64); val COMPLEX128 = of(GoBasicKind.COMPLEX128)
        val STRING = of(GoBasicKind.STRING); val UNSAFE_POINTER = of(GoBasicKind.UNSAFE_POINTER)
        val UNTYPED_BOOL = of(GoBasicKind.UNTYPED_BOOL); val UNTYPED_INT = of(GoBasicKind.UNTYPED_INT)
        val UNTYPED_RUNE = of(GoBasicKind.UNTYPED_RUNE); val UNTYPED_FLOAT = of(GoBasicKind.UNTYPED_FLOAT)
        val UNTYPED_COMPLEX = of(GoBasicKind.UNTYPED_COMPLEX); val UNTYPED_STRING = of(GoBasicKind.UNTYPED_STRING)
        val UNTYPED_NIL = of(GoBasicKind.UNTYPED_NIL); val INVALID = of(GoBasicKind.INVALID)

        /**
         * `byte` and `rune` are aliases of uint8 and int32: distinct instances that keep the spelling for rendering
         * only. [equals]/[hashCode] compare kinds, so they are identical to [UINT8]/[INT32] everywhere else.
         */
        val BYTE = GoBasicType(GoBasicKind.UINT8, "byte")
        val RUNE = GoBasicType(GoBasicKind.INT32, "rune")

        fun byName(name: String): GoBasicType? = when (name) {
            "byte" -> BYTE
            "rune" -> RUNE
            else -> GoBasicKind.entries.firstOrNull { !it.isUntyped && it.typeName == name && it != GoBasicKind.INVALID }?.let(::of)
        }
    }
}

/** A type that could not be determined; absorbs every operation. */
object GoUnknownType : GoType()

/** `[N]T`; [length] is null when the length expression is not a constant we can evaluate. */
data class GoArrayType(val elem: GoType, val length: Long?) : GoType() {
    override fun substitute(subst: GoSubstitution): GoType = elem.substitute(subst).let { if (it === elem) this else GoArrayType(it, length) }
}

data class GoSliceType(val elem: GoType) : GoType() {
    override fun substitute(subst: GoSubstitution): GoType = elem.substitute(subst).let { if (it === elem) this else GoSliceType(it) }
}

data class GoPointerType(val elem: GoType) : GoType() {
    override fun substitute(subst: GoSubstitution): GoType = elem.substitute(subst).let { if (it === elem) this else GoPointerType(it) }
}

data class GoMapType(val key: GoType, val value: GoType) : GoType() {
    override fun substitute(subst: GoSubstitution): GoType {
        val k = key.substitute(subst)
        val v = value.substitute(subst)
        return if (k === key && v === value) this else GoMapType(k, v)
    }
}

enum class GoChanDir { BOTH, SEND, RECV }

data class GoChanType(val elem: GoType, val dir: GoChanDir) : GoType() {
    override fun substitute(subst: GoSubstitution): GoType = elem.substitute(subst).let { if (it === elem) this else GoChanType(it, dir) }
}

/** Multiple values (call results); a single-value tuple is never created, use the value type. */
data class GoTupleType(val types: List<GoType>) : GoType() {
    override fun substitute(subst: GoSubstitution): GoType = GoTupleType(types.map { it.substitute(subst) })
}

/** A struct field. [declaration] is the field or embedded-field definition for navigation. */
class GoField(
    val name: String,
    val type: GoType,
    val embedded: Boolean,
    val tag: String?,
    val declaration: GoNamedElement?,
    /** Package path of the declaring package for unexported names (identity of `x` vs `other.x`). */
    val pkgPath: String?,
) {
    val isExported: Boolean get() = name.isNotEmpty() && Character.isUpperCase(name.codePointAt(0))
    fun withType(type: GoType): GoField = if (type === this.type) this else GoField(name, type, embedded, tag, declaration, pkgPath)
    override fun equals(other: Any?): Boolean = other is GoField && other.name == name && other.embedded == embedded &&
        other.type == type && other.tag == tag && other.pkgPath == pkgPath
    override fun hashCode(): Int = name.hashCode() * 31 + type.hashCode()
    override fun toString(): String = "$name ${GoTypeRenderer.render(type)}"
}

class GoStructType(val fields: List<GoField>) : GoType() {
    fun field(name: String): GoField? = fields.firstOrNull { it.name == name }
    override fun substitute(subst: GoSubstitution): GoType {
        var changed = false
        val mapped = fields.map { f -> f.withType(f.type.substitute(subst)).also { if (it !== f) changed = true } }
        return if (changed) GoStructType(mapped) else this
    }
    override fun equals(other: Any?): Boolean = other is GoStructType && other.fields == fields
    override fun hashCode(): Int = fields.hashCode()
}

/** A parameter or result of a signature. */
class GoParam @JvmOverloads constructor(val name: String?, val type: GoType, val declaration: GoNamedElement? = null) {
    fun withType(type: GoType): GoParam = if (type === this.type) this else GoParam(name, type, declaration)
    override fun equals(other: Any?): Boolean = other is GoParam && other.type == type
    override fun hashCode(): Int = type.hashCode()
    override fun toString(): String = if (name == null) GoTypeRenderer.render(type) else "$name ${GoTypeRenderer.render(type)}"
}

class GoSignatureType @JvmOverloads constructor(
    val params: List<GoParam>,
    val results: List<GoParam>,
    val variadic: Boolean,
    val typeParams: List<GoTypeParamType> = emptyList(),
    val receiver: GoParam? = null,
    /** Type arguments already applied to a partially instantiated generic signature (needed to substitute the remaining parameters' constraints). */
    val partialSubst: GoSubstitution = GoSubstitution.EMPTY,
) : GoType() {
    val resultType: GoType
        get() = when (results.size) {
            0 -> GoTupleType(emptyList())
            1 -> results[0].type
            else -> GoTupleType(results.map { it.type })
        }
    val isGeneric: Boolean get() = typeParams.isNotEmpty()

    override fun substitute(subst: GoSubstitution): GoType {
        var changed = false
        val p = params.map { x -> x.withType(x.type.substitute(subst)).also { if (it !== x) changed = true } }
        val r = results.map { x -> x.withType(x.type.substitute(subst)).also { if (it !== x) changed = true } }
        val remaining = typeParams.filter { !subst.has(it) }
        if (remaining.size != typeParams.size) changed = true
        return if (changed) GoSignatureType(p, r, variadic, remaining, receiver, partialSubst + subst) else this
    }
    override fun equals(other: Any?): Boolean = other is GoSignatureType && other.variadic == variadic &&
        other.params == params && other.results == results && other.typeParams.size == typeParams.size
    override fun hashCode(): Int = params.hashCode() * 31 + results.hashCode()
}

/** A method of a named or interface type; [declaration] is the method declaration or method spec. */
class GoMethod @JvmOverloads constructor(val name: String, val signature: GoSignatureType, val declaration: GoNamedElement?, val pointerReceiver: Boolean = false, val pkgPath: String? = null) {
    val isExported: Boolean get() = name.isNotEmpty() && Character.isUpperCase(name.codePointAt(0))
    fun withSignature(signature: GoSignatureType): GoMethod =
        if (signature === this.signature) this else GoMethod(name, signature, declaration, pointerReceiver, pkgPath)
    override fun toString(): String = "$name${GoTypeRenderer.render(signature).removePrefix("func")}"
}

/** A term of a union / constraint element: `T` or `~T`. */
data class GoTerm(val tilde: Boolean, val type: GoType) {
    fun substitute(subst: GoSubstitution): GoTerm = type.substitute(subst).let { if (it === type) this else GoTerm(tilde, it) }
}

/** `A | ~B | C` as it appears in a constraint; only meaningful inside interfaces. */
data class GoUnionType(val terms: List<GoTerm>) : GoType() {
    override fun substitute(subst: GoSubstitution): GoType = GoUnionType(terms.map { it.substitute(subst) })
}

/**
 * An interface: explicitly declared [methods], [embedded] types (interfaces, or type terms /
 * unions for constraints). [allMethods] is the full method set including embedded interfaces,
 * computed lazily with a recursion guard.
 */
class GoInterfaceType @JvmOverloads constructor(val methods: List<GoMethod>, val embedded: List<GoType>, val comparableMarker: Boolean = false, val implicit: Boolean = false) : GoType() {
    val allMethods: List<GoMethod> by lazy { computeAllMethods() }

    /** True for `comparable` and for constraints embedding it (spec "Satisfying a type constraint"). */
    val isComparableConstraint: Boolean by lazy {
        comparableMarker || embedded.any { e -> (e.underlying() as? GoInterfaceType)?.let { RecursionManager.doPreventingRecursion(it, false) { it.isComparableConstraint } } == true }
    }

    /** True for `any` / `interface{}` with no methods and no type terms. */
    val isEmpty: Boolean get() = methods.isEmpty() && embedded.isEmpty() && !comparableMarker

    /** True when the interface can only be used as a constraint (type terms or `comparable`). */
    val isConstraintOnly: Boolean get() = hasTypeTerms || isComparableConstraint

    /** True when the interface has type terms (it is a constraint, not a basic interface). */
    val hasTypeTerms: Boolean get() = embedded.any { it !is GoInterfaceType && it !is GoNamedType && it !is GoTypeParamType } ||
        embedded.any { it is GoNamedType && (it.underlying() as? GoInterfaceType)?.hasTypeTerms == true }

    /** The type terms of this constraint (flattened over embedded interfaces); null when there are none. */
    val typeTerms: List<GoTerm>? by lazy { computeTerms() }

    fun method(name: String): GoMethod? = allMethods.firstOrNull { it.name == name }

    private fun computeAllMethods(): List<GoMethod> {
        val result = LinkedHashMap<String, GoMethod>()
        methods.forEach { result[it.name] = it }
        for (e in embedded) {
            val iface = e.underlying() as? GoInterfaceType ?: continue
            val nested = RecursionManager.doPreventingRecursion(iface, false) { iface.allMethods } ?: emptyList()
            nested.forEach { result.putIfAbsent(it.name, it) }
        }
        return result.values.toList()
    }

    private fun computeTerms(): List<GoTerm>? {
        var terms: List<GoTerm>? = null
        for (e in embedded) {
            val list: List<GoTerm> = when (e) {
                is GoUnionType -> e.terms
                is GoInterfaceType, is GoNamedType -> {
                    val iface = e.underlying() as? GoInterfaceType ?: continue
                    (RecursionManager.doPreventingRecursion(iface, false) { iface.typeTerms } ?: null) ?: continue
                }
                is GoTypeParamType -> continue
                else -> listOf(GoTerm(false, e))
            }
            terms = if (terms == null) list else intersectTerms(terms, list)
        }
        return terms
    }

    private fun intersectTerms(a: List<GoTerm>, b: List<GoTerm>): List<GoTerm> {
        val out = ArrayList<GoTerm>()
        for (x in a) for (y in b) {
            val t = when {
                x.tilde && y.tilde -> if (GoTypePredicates.identical(x.type, y.type)) x else null
                x.tilde -> if (GoTypePredicates.identical(x.type, y.type.underlying())) y else null
                y.tilde -> if (GoTypePredicates.identical(y.type, x.type.underlying())) x else null
                else -> if (GoTypePredicates.identical(x.type, y.type)) x else null
            }
            if (t != null) out += t
        }
        return out
    }

    override fun substitute(subst: GoSubstitution): GoType {
        var changed = false
        val m = methods.map { x -> x.withSignature(x.signature.substitute(subst) as GoSignatureType).also { if (it !== x) changed = true } }
        val e = embedded.map { x -> x.substitute(subst).also { if (it !== x) changed = true } }
        return if (changed) GoInterfaceType(m, e, comparableMarker, implicit) else this
    }
    override fun equals(other: Any?): Boolean = other is GoInterfaceType && other.comparableMarker == comparableMarker &&
        other.methods.map { it.name to it.signature } == methods.map { it.name to it.signature } && other.embedded == embedded
    override fun hashCode(): Int = methods.size * 31 + embedded.hashCode() + (if (comparableMarker) 7 else 0)
}

/** Resolves lazily computed parts of named types and type parameters (implemented by the inference engine). */
interface GoTypeSource {
    fun underlyingOf(named: GoNamedType): GoType
    fun methodsOf(named: GoNamedType): List<GoMethod>
    fun boundOf(param: GoTypeParamType): GoType
    /** The package path of the declaring file, for unexported-name identity. */
    fun packagePathOf(named: GoNamedType): String?
}

/**
 * A defined (named) type: `type T ...`. Generic types carry [typeArgs] once instantiated;
 * [origin] is the uninstantiated generic type. Equality is declaration + type arguments.
 */
class GoNamedType @JvmOverloads constructor(
    val declaration: GoTypeSpec,
    val typeArgs: List<GoType>,
    val source: GoTypeSource,
    val origin: GoNamedType? = null,
) : GoType() {
    val name: String get() = declaration.name ?: "?"
    val isGeneric: Boolean get() = declaration.typeParameters != null && typeArgs.isEmpty()
    val isInstantiated: Boolean get() = typeArgs.isNotEmpty()

    private val underlyingLazy: GoType by lazy {
        RecursionManager.doPreventingRecursion(this, true) { source.underlyingOf(this) } ?: GoUnknownType
    }
    private val methodsLazy: List<GoMethod> by lazy {
        RecursionManager.doPreventingRecursion(this, true) { source.methodsOf(this) } ?: emptyList()
    }

    override fun underlying(): GoType = underlyingLazy

    /** Declared methods (not promoted ones), with receiver-type-parameter substitution applied. */
    val methods: List<GoMethod> get() = methodsLazy

    val pkgPath: String? get() = source.packagePathOf(this)

    fun instantiate(args: List<GoType>): GoNamedType = GoNamedType(declaration, args, source, origin ?: this)

    override fun substitute(subst: GoSubstitution): GoType {
        if (typeArgs.isEmpty()) return this
        var changed = false
        val args = typeArgs.map { a -> a.substitute(subst).also { if (it !== a) changed = true } }
        return if (changed) GoNamedType(declaration, args, source, origin ?: this) else this
    }
    override fun equals(other: Any?): Boolean = other is GoNamedType && other.declaration == declaration && other.typeArgs == typeArgs
    override fun hashCode(): Int = declaration.hashCode() * 31 + typeArgs.hashCode()
}

/** A type parameter; [bound] is its constraint interface (resolved lazily). */
class GoTypeParamType private constructor(
    val declaration: GoTypeParamDefinition,
    val index: Int,
    val source: GoTypeSource,
    /** 0 for the declared parameter; > 0 for a copy made by [renamed] (go/types `renameTParams`). */
    val generation: Int,
    private val renaming: Lazy<GoSubstitution>?,
) : GoType() {
    constructor(declaration: GoTypeParamDefinition, index: Int, source: GoTypeSource) : this(declaration, index, source, 0, null)

    val name: String get() = declaration.name ?: "?"
    val bound: GoType by lazy {
        val b = RecursionManager.doPreventingRecursion(declaration to generation, true) { source.boundOf(this) } ?: GoUnknownType
        renaming?.value?.let { b.substitute(it) } ?: b
    }

    /** The constraint's type set terms, when the constraint has any; null for method-only constraints. */
    val terms: List<GoTerm>? get() = (bound.underlying() as? GoInterfaceType)?.typeTerms

    /**
     * The single underlying type shared by every term of the constraint (`go/types` core type,
     * "common underlying type"); null when terms differ or there are none.
     */
    val coreType: GoType?
        get() {
            val ts = terms ?: return null
            if (ts.isEmpty()) return null
            val first = ts[0].type.underlying()
            if (ts.all { GoTypePredicates.identical(it.type.underlying(), first) }) return first
            // Channels: identical element types and compatible directions give the most restrictive channel type.
            val chans = ts.map { it.type.underlying() as? GoChanType ?: return null }
            if (chans.any { !GoTypePredicates.identical(it.elem, chans[0].elem) }) return null
            val dirs = chans.map { it.dir }.toSet() - GoChanDir.BOTH
            if (dirs.size > 1) return null
            return GoChanType(chans[0].elem, dirs.firstOrNull() ?: GoChanDir.BOTH)
        }

    /** The single non-tilde term of the constraint (`[T int]`), usable as the inferred type (go/types infer.go). */
    val singleExactTerm: GoType?
        get() = terms?.singleOrNull()?.takeIf { !it.tilde }?.type

    override fun underlying(): GoType = bound.underlying()
    override fun substitute(subst: GoSubstitution): GoType = subst[this] ?: this
    override fun equals(other: Any?): Boolean = other is GoTypeParamType && other.declaration == declaration && other.generation == generation
    override fun hashCode(): Int = declaration.hashCode() * 31 + generation

    companion object {
        private val generations = java.util.concurrent.atomic.AtomicInteger()

        /**
         * Fresh copies of [params] with a new identity (go/types `renameTParams`): a generic function
         * calling itself passes its own type parameters as arguments, so inference must not confuse
         * the callee's parameters with the caller's. Bounds are rewritten to refer to the copies.
         */
        @org.jetbrains.annotations.ApiStatus.Internal
        @JvmStatic
        fun renamed(params: List<GoTypeParamType>): List<GoTypeParamType> {
            val gen = generations.incrementAndGet()
            lateinit var subst: GoSubstitution
            val lazySubst = lazy { subst }
            val copies = params.map { GoTypeParamType(it.declaration, it.index, it.source, gen, lazySubst) }
            subst = GoSubstitution.of(params, copies)
            return copies
        }
    }
}

/** A type-parameter substitution. */
class GoSubstitution(val map: Map<GoTypeParamType, GoType>) {
    operator fun get(param: GoTypeParamType): GoType? = map[param]
    fun has(param: GoTypeParamType): Boolean = map.containsKey(param)
    val isEmpty: Boolean get() = map.isEmpty()
    operator fun plus(other: GoSubstitution): GoSubstitution = GoSubstitution(map + other.map)

    companion object {
        val EMPTY = GoSubstitution(emptyMap())
        fun of(params: List<GoTypeParamType>, args: List<GoType>): GoSubstitution =
            if (params.isEmpty()) EMPTY else GoSubstitution(params.zip(args).toMap())
    }
}
