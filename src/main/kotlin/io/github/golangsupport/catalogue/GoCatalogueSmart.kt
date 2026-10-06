package io.github.golangsupport.catalogue

import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoIdioms
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.infer.GoTypeBuilder
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * Smart completion from the catalogue: which functions, variables and constants of the packages a file may import give a value of the
 * type expected at the caret. The catalogue keeps types as the text of the scanner, so a type is matched by a key, not by the type
 * checker: a basic type by its name (`int`, `uint8` for `byte`), `error`, and a named type with an optional pointer — `net/http.Request`
 * when it is written in its own package (`*Request`), `@http.Request` when written qualified in another one (`*http.Request`: the
 * scanner does not keep the imports, so the package is known by its name only). Slices, maps, functions, generic functions and
 * untyped constants (the scanner keeps no value) are not matched.
 */
object GoCatalogueSmart {
    private val BASIC = setOf(
        "bool", "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
        "float32", "float64", "complex64", "complex128", "error",
    )
    private val ALIASES = mapOf("byte" to "uint8", "rune" to "int32")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The key of the type of the value [symbol] of the package [importPath] gives (the first result of a function); null when it has none to match. */
    fun resultKey(symbol: GoSymbol, importPath: String): String? {
        val signature = symbol.signature ?: return null
        if (signature.endsWith("…")) return null
        val text = when (symbol.kind) {
            GoDeclarationKind.FUNCTION -> {
                if (signature.trimStart().startsWith("[")) return null
                GoIdioms.splitSignature(signature).second.firstOrNull()?.type ?: return null
            }
            GoDeclarationKind.VAR, GoDeclarationKind.CONST -> signature
            else -> return null
        }
        return keyOf(text, importPath)
    }

    /** `int`, `*Request` in `net/http` -> `*net/http.Request`, `time.Duration` -> `@time.Duration`; null for a type that is not one of those. */
    fun keyOf(typeText: String, importPath: String): String? {
        var text = typeText.trim()
        val pointer = text.startsWith("*")
        if (pointer) text = text.substring(1).trim()
        val star = if (pointer) "*" else ""
        val qualifier = text.substringBefore('.', "")
        val name = text.substringAfter('.')
        if (!IDENTIFIER.matches(name) || qualifier.isNotEmpty() && !IDENTIFIER.matches(qualifier)) return null
        if (qualifier.isNotEmpty()) return "$star@$qualifier.$name"
        val basic = ALIASES[name] ?: name
        return if (basic in BASIC) "$star$basic" else "$star$importPath.$name"
    }

    /** The keys a value of [type] may have in the catalogue: the basic types, `error` and named types of a package, with a pointer or not. */
    fun expectedKeys(type: GoType): List<String> {
        val pointer = type is GoPointerType
        val inner = if (type is GoPointerType) type.elem else type
        val star = if (pointer) "*" else ""
        return when {
            inner is GoBasicType -> if (inner.isUntyped || inner.kind.typeName !in BASIC) emptyList() else listOf(star + inner.kind.typeName)
            inner is GoNamedType -> {
                val path = inner.pkgPath ?: return emptyList()
                if (inner.isGeneric || inner.isInstantiated) return emptyList()
                val packageName = (inner.declaration.containingFile as? GoFile)?.packageName ?: path.substringAfterLast('/')
                listOf("$star$path.${inner.name}", "$star@$packageName.${inner.name}")
            }
            !pointer && GoTypePredicates.identical(inner, GoTypeBuilder.ERROR) -> listOf("error")
            else -> emptyList()
        }
    }
}
