package io.github.golangsupport.lang

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/** The type questions of the postfix templates; an unknown type answers yes to all of them but [isVoid], [isInteger], [isString]. */
object GoPostfixTypes {
    fun known(type: GoType): Boolean = type !is GoUnknownType && !(type is GoBasicType && type.kind == GoBasicKind.INVALID)

    private fun basic(type: GoType): GoBasicType? = type.underlying() as? GoBasicType

    fun isBool(type: GoType): Boolean = !known(type) || basic(type)?.kind?.isBoolean == true
    fun isInteger(type: GoType): Boolean = basic(type)?.kind?.isInteger == true
    fun isString(type: GoType): Boolean = basic(type)?.kind?.isString == true
    fun isFloat(type: GoType): Boolean = !known(type) || basic(type)?.kind?.isFloat == true
    fun isComplex(type: GoType): Boolean = !known(type) || basic(type)?.kind?.isComplex == true
    fun isError(type: GoType): Boolean = !known(type) || GoReturnValues.isError(type)
    fun isVoid(type: GoType): Boolean = type is GoTupleType && type.types.isEmpty()
    fun isValue(type: GoType): Boolean = !isVoid(type) && type !is GoTupleType
    fun isPointer(type: GoType): Boolean = !known(type) || type.underlying() is GoPointerType
    fun isMap(type: GoType): Boolean = !known(type) || type.underlying() is GoMapType

    /** What `close` takes: a channel one can send to. */
    fun isClosable(type: GoType): Boolean = !known(type) || (type.underlying() as? GoChanType)?.dir?.let { it != GoChanDir.RECV } == true

    fun isNillable(type: GoType): Boolean {
        if (!known(type)) return true
        return when (val u = type.underlying()) {
            is GoPointerType, is GoSliceType, is GoMapType, is GoChanType, is GoSignatureType -> true
            is GoInterfaceType -> !u.isConstraintOnly
            is GoBasicType -> u.kind == GoBasicKind.UNSAFE_POINTER || u.kind == GoBasicKind.UNTYPED_NIL
            else -> false
        }
    }

    fun isSlice(type: GoType): Boolean = !known(type) || type.underlying() is GoSliceType || type is GoTypeParamType

    /** What `len` takes: slices, arrays (and pointers to them), strings, maps, channels. */
    fun hasLength(type: GoType): Boolean {
        if (!known(type) || type is GoTypeParamType) return true
        return when (val u = type.underlying()) {
            is GoSliceType, is GoArrayType, is GoMapType, is GoChanType -> true
            is GoPointerType -> u.elem.underlying() is GoArrayType
            is GoBasicType -> u.kind.isString
            else -> false
        }
    }

    /** What `cap` takes: slices, arrays (and pointers to them), channels. */
    fun hasCapacity(type: GoType): Boolean {
        if (!known(type) || type is GoTypeParamType) return true
        return when (val u = type.underlying()) {
            is GoSliceType, is GoArrayType, is GoChanType -> true
            is GoPointerType -> u.elem.underlying() is GoArrayType
            else -> false
        }
    }

    /** A collection whose values are its elements (a slice, an array, a channel; unknown too): `name` of `names` names them. */
    fun hasElements(type: GoType): Boolean {
        if (!known(type)) return true
        return when (val u = type.underlying()) {
            is GoSliceType, is GoArrayType, is GoChanType -> true
            is GoPointerType -> u.elem.underlying() is GoArrayType
            else -> false
        }
    }

    /** What `for range` goes over: slices, arrays, pointers to arrays, strings, maps, receiving channels, integers, iterator functions. */
    fun isRangeable(type: GoType): Boolean {
        if (!known(type) || type is GoTypeParamType) return true
        return when (val u = type.underlying()) {
            is GoSliceType, is GoArrayType, is GoMapType -> true
            is GoChanType -> u.dir != GoChanDir.SEND
            is GoPointerType -> u.elem.underlying() is GoArrayType
            is GoBasicType -> u.kind.isString || u.kind.isInteger
            is GoSignatureType -> u.results.isEmpty() && (u.params.singleOrNull()?.type?.underlying() as? GoSignatureType)?.params?.size in 0..2
            else -> false
        }
    }

    /** The variables of `for … := range x` with their defaults: `k, v` for a map, `v` for a channel, `i` for an int. */
    fun rangeVariables(type: GoType): List<Pair<String, String>> {
        if (!known(type)) return listOf("KEY" to "_", "VALUE" to "v")
        return when (val u = type.underlying()) {
            is GoMapType -> listOf("KEY" to "k", "VALUE" to "v")
            is GoChanType -> listOf("VALUE" to "v")
            is GoBasicType -> if (u.kind.isInteger) listOf("KEY" to "i") else listOf("KEY" to "i", "VALUE" to "r")
            is GoSignatureType -> when ((u.params.singleOrNull()?.type?.underlying() as? GoSignatureType)?.params?.size) {
                0 -> emptyList()
                1 -> listOf("VALUE" to "v")
                else -> listOf("KEY" to "k", "VALUE" to "v")
            }
            else -> listOf("KEY" to "_", "VALUE" to "v")
        }
    }

    /** Whether the element of a slice is ordered (`slices.Sort` takes it). */
    fun orderedElement(type: GoType): Boolean = ((type.underlying() as? GoSliceType)?.elem?.underlying() as? GoBasicType)?.kind?.isOrdered == true

    /** `Strings`, `Ints`, `Float64s` of package sort for `[]string`, `[]int`, `[]float64` (the element unnamed: `sort.Strings` takes nothing else). */
    fun sortFunction(type: GoType): String? = when (((type.underlying() as? GoSliceType)?.elem as? GoBasicType)?.kind) {
        GoBasicKind.STRING -> "Strings"
        GoBasicKind.INT -> "Ints"
        GoBasicKind.FLOAT64 -> "Float64s"
        else -> null
    }

    /** Whether [type] has the methods of `sort.Interface` (`Len`, `Less`, `Swap`), so `sort.Sort` takes [expression]. */
    fun implementsSort(expression: GoExpression, type: GoType): Boolean {
        if (!known(type) || DumbService.isDumb(expression.project)) return false
        val names = try { GoSemanticService.getInstance(expression.project).methodsOf(type).map { it.name }.toSet() } catch (_: IndexNotReadyException) { return false }
        return names.containsAll(listOf("Len", "Less", "Swap"))
    }
}
