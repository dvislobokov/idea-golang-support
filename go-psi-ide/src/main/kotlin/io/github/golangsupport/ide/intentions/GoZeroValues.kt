package io.github.golangsupport.ide.intentions

import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType

/**
 * Zero values of types as Go source: `0`, `""`, `false`, `nil` (pointers, slices, maps, channels, functions, interfaces),
 * `T{}` for structs and arrays, `*new(T)` for type parameters. [typeSource] writes a type as the current file spells it
 * (qualified by the import name of its package): completion's `iferr` snippet and the intentions pass their own.
 */
object GoZeroValues {

    fun of(type: GoType, typeSource: (GoType) -> String): String {
        if (type is GoTypeParamType) return "*new(${type.name})"
        return when (val u = type.underlying()) {
            is GoBasicType -> when {
                u.kind.isBoolean -> "false"
                u.kind.isString -> "\"\""
                u.kind.isNumeric -> "0"
                else -> "nil"
            }
            is GoPointerType, is GoSliceType, is GoMapType, is GoChanType, is GoSignatureType, is GoInterfaceType -> "nil"
            is GoStructType, is GoArrayType -> typeSource(type) + "{}"
            else -> "nil"
        }
    }

    /** Whether the zero value of [type] is `nil`: pointers, slices, maps, channels, functions and interfaces (named or not). */
    fun isNilable(type: GoType): Boolean = when (type.underlying()) {
        is GoPointerType, is GoSliceType, is GoMapType, is GoChanType, is GoSignatureType, is GoInterfaceType -> true
        else -> false
    }

    /** The predeclared `error` (the semantic layer models it as the unnamed interface `interface{ Error() string }`). */
    fun isError(type: GoType): Boolean {
        if (type is GoNamedType) {
            val file = type.declaration.containingFile as? GoFile ?: return false
            return type.name == "error" && file.packageName == "builtin"
        }
        return type is GoInterfaceType && type.embedded.isEmpty() && type.methods.singleOrNull()?.let { m ->
            m.name == "Error" && m.signature.params.isEmpty() && m.signature.results.singleOrNull()?.type == GoBasicType.STRING
        } == true
    }
}
