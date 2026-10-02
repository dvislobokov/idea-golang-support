package io.github.golangsupport.ide.inspections.printf

import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Whether an argument type can be printed by a verb, after vet's `matchArgType`: `%v`/`%T` take anything, a `fmt.Formatter`
 * anything, `%s`-like verbs anything with `String() string` or `Error() string`; interfaces and unknown types always match
 * (the dynamic value decides, and an unknown type must never report); slices, arrays and maps match by their elements, structs
 * by all fields, a top-level pointer to a composite by what it points to.
 */
class GoPrintfTypes(private val service: GoSemanticService) {

    fun matches(type: GoType, args: Int): Boolean = Matcher(args).match(type, true)

    /** `error` or a type whose method set has `Error() string` (vet: convertible to `error`). */
    fun isError(type: GoType): Boolean = hasStringMethod(type, "Error", pointerToo = false)

    /** Has `String() string` or `Error() string` (with a pointer receiver too: vet is lenient about addressability). */
    fun isStringer(type: GoType): Boolean {
        if ((type as? GoBasicType)?.kind == GoBasicKind.UNTYPED_NIL) return false
        return hasStringMethod(type, "Error", pointerToo = true) || hasStringMethod(type, "String", pointerToo = true)
    }

    /** Implements `fmt.Formatter` (`Format(fmt.State, rune)`): any verb is then the type's business. */
    fun isFormatter(type: GoType): Boolean = methods(type, pointerToo = true).any { it.name == "Format" && it.signature.params.size == 2 && it.signature.results.isEmpty() }

    /** Whether the type is known well enough to report on (unknown parts never report). */
    fun isKnown(type: GoType): Boolean = type !is GoUnknownType && GoTypePredicates.isKnown(type)

    private fun hasStringMethod(type: GoType, name: String, pointerToo: Boolean): Boolean = methods(type, pointerToo).any { m ->
        m.name == name && m.signature.params.isEmpty() && m.signature.results.size == 1 &&
            (m.signature.results[0].type as? GoBasicType)?.kind == GoBasicKind.STRING
    }

    private fun methods(type: GoType, pointerToo: Boolean): List<GoMethod> {
        val iface = type.underlying() as? GoInterfaceType
        if (iface != null && type !is GoTypeParamType) return iface.allMethods
        val own = service.methodsOf(type)
        if (!pointerToo || type is GoPointerType || type.underlying() is GoInterfaceType) return own
        return own + service.methodsOf(GoPointerType(type))
    }

    private inner class Matcher(private val args: Int) {
        private val seen = HashSet<Any>()

        fun match(type: GoType, topLevel: Boolean): Boolean {
            if (args == GoPrintfArg.ERROR) return type is GoUnknownType || type is GoTypeParamType || isError(type) || (type as? GoBasicType)?.kind == GoBasicKind.UNTYPED_NIL
            if ((args and GoPrintfArg.ANY) == GoPrintfArg.ANY) return true
            if (type is GoUnknownType) return true
            if (isFormatter(type)) return true
            if (has(GoPrintfArg.STRING) && isStringer(type)) return true
            if (type is GoTypeParamType) {
                val terms = type.terms ?: return true
                if (terms.isEmpty()) return true
                return terms.all { match(it.type, topLevel) }
            }
            // `type T []T`: a type already on the walk matches (vet's `seen`).
            val identity: Any = (type as? GoNamedType)?.declaration ?: type
            if (type is GoNamedType && !seen.add(identity)) return true
            return when (val u = type.underlying()) {
                is GoUnknownType -> true
                is GoSignatureType, is GoChanType -> has(GoPrintfArg.POINTER)
                is GoMapType -> has(GoPrintfArg.POINTER) || (match(u.key, false) && match(u.value, false))
                is GoArrayType -> isBytes(u.elem) && has(GoPrintfArg.STRING) || match(u.elem, false)
                // `%p` prints a slice's address.
                is GoSliceType -> isBytes(u.elem) && has(GoPrintfArg.STRING) || args == GoPrintfArg.POINTER || match(u.elem, false)
                is GoPointerType -> pointer(u, topLevel)
                is GoStructType -> u.fields.all { f ->
                    match(f.type, false) && !(has(GoPrintfArg.STRING) && !f.isExported && isStringer(f.type))
                }
                is GoInterfaceType -> true
                is GoBasicType -> basic(u.kind)
                else -> true
            }
        }

        private fun pointer(p: GoPointerType, topLevel: Boolean): Boolean {
            if (args == GoPrintfArg.POINTER) return true
            val elem = p.elem
            if (elem is GoUnknownType || elem is GoTypeParamType) return true
            return when (val under = elem.underlying()) {
                is GoStructType, is GoArrayType, is GoSliceType, is GoMapType -> topLevel && match(under, false)
                is GoUnknownType -> true
                else -> has(GoPrintfArg.POINTER)
            }
        }

        private fun basic(kind: GoBasicKind): Boolean = when {
            kind == GoBasicKind.INVALID -> true
            kind == GoBasicKind.UNTYPED_NIL -> false
            kind == GoBasicKind.UNSAFE_POINTER -> has(GoPrintfArg.POINTER or GoPrintfArg.INT)
            kind == GoBasicKind.UNTYPED_RUNE -> has(GoPrintfArg.INT or GoPrintfArg.RUNE)
            kind.isBoolean -> has(GoPrintfArg.BOOL)
            kind.isInteger -> has(GoPrintfArg.INT)
            kind.isFloat -> has(GoPrintfArg.FLOAT)
            kind.isComplex -> has(GoPrintfArg.COMPLEX)
            kind.isString -> has(GoPrintfArg.STRING)
            else -> true
        }

        private fun has(bits: Int): Boolean = (args and bits) != 0

        private fun isBytes(t: GoType): Boolean = (t.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8
    }

    companion object {
        /** The verb that prints [type] unambiguously, for "Replace %d with %s"; null when there is no single right one. */
        fun suggestedVerb(type: GoType, types: GoPrintfTypes): Char? {
            if (types.isStringer(type)) return 'v'
            val u = type.underlying()
            if (u is GoSliceType && (u.elem.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8) return 's'
            val kind = (u as? GoBasicType)?.kind ?: return null
            return when {
                kind == GoBasicKind.UNTYPED_NIL || kind == GoBasicKind.UNSAFE_POINTER -> null
                kind.isString -> 's'
                kind.isInteger -> 'd'
                kind.isBoolean -> 't'
                kind.isFloat -> 'f'
                kind.isComplex -> 'g'
                else -> null
            }
        }
    }
}
