package io.github.golangsupport.semantic.infer

import io.github.golangsupport.semantic.types.*

import org.jetbrains.annotations.ApiStatus

/**
 * Type sizes and alignments of the gc compiler on 64-bit targets (go/types `gcSizes` with
 * WordSize = MaxAlign = 8), used to fold `unsafe.Sizeof`, `unsafe.Alignof` and `unsafe.Offsetof`
 * into constants. Null means the size is not known: a type parameter (`hasVarSize`), an unknown
 * type, or a type too deep or too large to evaluate.
 */
@ApiStatus.Internal
object GoSizes {
    private const val WORD = 8L
    private const val MAX_ALIGN = 8L

    fun alignof(t: GoType, depth: Int = 0): Long? {
        if (depth > 32 || t is GoTypeParamType) return null
        return when (val u = t.underlying()) {
            is GoArrayType -> alignof(u.elem, depth + 1)
            is GoStructType -> u.fields.fold(1L) { acc, f -> maxOf(acc, alignof(f.type, depth + 1) ?: return null) }
            is GoSliceType, is GoInterfaceType -> WORD
            is GoBasicType -> when {
                u.kind.isString -> WORD
                // complex64 aligns like float32, complex128 like float64.
                u.kind == GoBasicKind.COMPLEX64 -> 4
                u.kind == GoBasicKind.COMPLEX128 -> 8
                else -> basicSize(u.kind)?.coerceIn(1, MAX_ALIGN)
            }
            is GoPointerType, is GoMapType, is GoChanType, is GoSignatureType -> WORD
            else -> null
        }
    }

    fun sizeof(t: GoType, depth: Int = 0): Long? {
        if (depth > 32 || t is GoTypeParamType) return null
        return when (val u = t.underlying()) {
            is GoBasicType -> if (u.kind.isString) 2 * WORD else basicSize(u.kind)
            is GoArrayType -> {
                val n = u.length ?: return null
                if (n <= 0L) return 0
                val esize = sizeof(u.elem, depth + 1) ?: return null
                if (esize == 0L) return 0
                if (esize > Long.MAX_VALUE / n) return null
                esize * n
            }
            is GoSliceType -> 3 * WORD
            is GoStructType -> {
                if (u.fields.isEmpty()) return 0
                val offsets = offsetsof(u, depth) ?: return null
                val offset = offsets.last()
                var size = sizeof(u.fields.last().type, depth + 1) ?: return null
                // gc: the last field of a non-zero-sized struct may not have size 0 (&s.last would point past the object).
                if (offset > 0 && size == 0L) size = 1
                align(offset + size, alignof(u, depth) ?: return null)
            }
            is GoInterfaceType -> 2 * WORD
            is GoPointerType, is GoMapType, is GoChanType, is GoSignatureType -> WORD
            else -> null
        }
    }

    /** Field offsets of [s] in declaration order. */
    fun offsetsof(s: GoStructType, depth: Int = 0): List<Long>? {
        var offset = 0L
        return s.fields.map { f ->
            val a = alignof(f.type, depth + 1) ?: return null
            offset = align(offset, a)
            val o = offset
            offset += sizeof(f.type, depth + 1) ?: return null
            o
        }
    }

    private fun basicSize(kind: GoBasicKind): Long? = when (kind) {
        GoBasicKind.BOOL, GoBasicKind.INT8, GoBasicKind.UINT8 -> 1
        GoBasicKind.INT16, GoBasicKind.UINT16 -> 2
        GoBasicKind.INT32, GoBasicKind.UINT32, GoBasicKind.FLOAT32 -> 4
        GoBasicKind.INT64, GoBasicKind.UINT64, GoBasicKind.FLOAT64, GoBasicKind.COMPLEX64 -> 8
        GoBasicKind.COMPLEX128 -> 16
        GoBasicKind.INT, GoBasicKind.UINT, GoBasicKind.UINTPTR, GoBasicKind.UNSAFE_POINTER -> WORD
        else -> null
    }

    private fun align(x: Long, a: Long): Long = (x + a - 1) / a * a
}
