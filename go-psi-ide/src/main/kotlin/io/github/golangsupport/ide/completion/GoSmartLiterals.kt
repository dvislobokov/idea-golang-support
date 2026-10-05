package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoParam
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType

/**
 * Values smart completion writes for the expected type when no name in scope has it: `T{}` / `&T{}` for a struct, `make(T)` /
 * `make(T, 0)` for a map, slice or channel, a `func(...) {}` literal for a function type, `""` and `0` for strings and numbers
 * (`nil`, `true` and `false` are universe constants, already candidates of the scope). The type is written as the file sees it;
 * a type of a package the file does not import gets no literal (it could not be written without a new import).
 */
class GoSmartLiterals(private val context: GoCompletionContext) {
    private val semantics = context.semantics

    fun collect(expected: GoType, out: MutableList<GoCandidate>) {
        val underlying = expected.underlying()
        when {
            expected is GoTypeParamType -> return
            expected is GoNamedType && underlying is GoStructType -> source(expected)?.let { literal("$it{}", 1, expected, it) }?.let(out::add)
            expected is GoPointerType && expected.elem is GoNamedType && expected.elem.underlying() is GoStructType ->
                source(expected.elem)?.let { literal("&$it{}", 1, expected, it) }?.let(out::add)
            underlying is GoSliceType -> source(expected)?.let { literal("make($it, 0)", 0, expected, "make") }?.let(out::add)
            underlying is GoMapType || underlying is GoChanType -> source(expected)?.let { literal("make($it)", 0, expected, "make") }?.let(out::add)
            underlying is GoSignatureType -> collectFunctionLiteral(expected, out)
            underlying is GoBasicType && underlying.kind.isString -> out += literal("\"\"", 1, expected, null)
            underlying is GoBasicType && underlying.kind.isNumeric -> out += literal("0", 0, expected, null)
        }
    }

    /**
     * The `func(a int) error {}` literal for an expected function type, offered by basic completion too (as GoLand: an argument, a
     * `return`, an assignment or a field whose type is a function gets the literal first, `fu` matches it); nothing for a type parameter.
     */
    fun collectFunctionLiteral(expected: GoType, out: MutableList<GoCandidate>) {
        if (expected is GoTypeParamType) return
        val signature = expected.underlying() as? GoSignatureType ?: return
        functionLiteral(signature)?.let { literal(it, 1, expected, "func") }?.let(out::add)
    }

    /** `go`/`defer` take a call: the literal called in place, caret inside the body (as GoLand's `func() {}()` after `defer`). */
    fun deferredCall(): GoCandidate = literal("func() {}()", 3, null, "func")

    private fun literal(text: String, caretFromEnd: Int, type: GoType?, alias: String?): GoCandidate = GoCandidate(
        text, GoCandidateKind.LITERAL, GoScopeLevel.KEYWORD, valueType = type,
        insertHandler = caretHandler(caretFromEnd), lookupStrings = listOfNotNull(alias?.substringAfterLast('.')).filter { it != text },
    )

    /** `func(a int, b ...string) (int, error) {}`: parameter names of the type, or names after the types (`w`, `r`
     *  for `http.HandleFunc`, whose signature has none: GoLand does the same) when it has none. */
    private fun functionLiteral(signature: GoSignatureType): String? {
        if (signature.isGeneric) return null
        val named = signature.params.all { !it.name.isNullOrEmpty() }
        val used = HashSet<String>()
        val params = signature.params.mapIndexed { i, p ->
            val variadic = signature.variadic && i == signature.params.lastIndex
            val type = if (variadic) (p.type as? GoSliceType)?.elem?.let(::source)?.let { "...$it" } else source(p.type)
            val name = if (named) p.name!! else GoNameSuggestions.parameterName(type?.removePrefix("...") ?: return null) { it in used }.also(used::add)
            name + " " + (type ?: return null)
        }
        val results = results(signature.results) ?: return null
        return "func(" + params.joinToString(", ") + ")" + results + " {}"
    }

    private fun results(results: List<GoParam>): String? {
        if (results.isEmpty()) return ""
        val types = results.map { source(it.type) ?: return null }
        val named = results.all { !it.name.isNullOrEmpty() && it.name != "_" }
        if (results.size == 1 && !named) return " " + types[0]
        val parts = if (named) results.mapIndexed { i, r -> r.name + " " + types[i] } else types
        return " (" + parts.joinToString(", ") + ")"
    }

    /** [type] as written in the current file; null when it cannot be written there (a package not imported, an unnamed struct). */
    fun source(type: GoType): String? = when (type) {
        is GoBasicType -> if (type.isUntyped) null else type.name
        is GoNamedType -> named(type)
        is GoTypeParamType -> type.name
        is GoPointerType -> source(type.elem)?.let { "*$it" }
        is GoSliceType -> source(type.elem)?.let { "[]$it" }
        is GoArrayType -> type.length?.let { n -> source(type.elem)?.let { "[$n]$it" } }
        is GoMapType -> source(type.key)?.let { k -> source(type.value)?.let { "map[$k]$it" } }
        is GoChanType -> source(type.elem)?.let {
            when (type.dir) {
                GoChanDir.BOTH -> "chan $it"
                GoChanDir.SEND -> "chan<- $it"
                GoChanDir.RECV -> "<-chan $it"
            }
        }
        is GoInterfaceType -> if (type.isEmpty) "any" else null
        is GoSignatureType -> if (type.isGeneric) null else {
            val params = type.params.mapIndexed { i, p ->
                val variadic = type.variadic && i == type.params.lastIndex
                (if (variadic) (p.type as? GoSliceType)?.elem?.let(::source)?.let { "...$it" } else source(p.type)) ?: return null
            }
            "func(" + params.joinToString(", ") + ")" + (results(type.results.map { GoParam(null, it.type) }) ?: return null)
        }
        else -> null
    }

    private fun named(type: GoNamedType): String? {
        if (type.isGeneric) return null
        val args = if (type.typeArgs.isEmpty()) "" else "[" + type.typeArgs.map { source(it) ?: return null }.joinToString(", ") + "]"
        val pkg = type.pkgPath
        val universe = GoUniverse.declarations(context.file.project)[type.name] === type.declaration
        if (pkg == null || universe || pkg == semantics.packagePath) return type.name + args
        val spec = semantics.imports.firstOrNull { it.path == pkg && !it.isBlank } ?: return null
        return (if (spec.isDot) "" else semantics.importName(spec) + ".") + type.name + args
    }

    private fun caretHandler(caretFromEnd: Int): InsertHandler<LookupElement>? =
        if (caretFromEnd == 0) null else InsertHandler { ctx, _ -> ctx.editor.caretModel.moveToOffset(ctx.tailOffset - caretFromEnd) }
}
