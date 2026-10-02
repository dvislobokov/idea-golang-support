package io.github.golangsupport.ide.hints

import io.github.golangsupport.ide.documentation.GoDocSignature
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.infer.GoSizes
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeRenderer

/** The pure rules of the inlay hints: what a parameter hint says and when it says nothing, constant texts, struct layouts. */
object GoInlayHints {
    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The longest text of a constant value hint (the platform cuts a hint at about this length too); a longer one ends with an ellipsis, the tooltip has it all. */
    const val MAX_VALUE_CHARS = 30

    /**
     * The label of the argument hint of [paramName] (`name:`, `args...:` for the first variadic argument), or null when it would say
     * nothing new (the gopls `parameterNames` hint, with its noise cut):
     * - the parameter has no name or is `_`;
     * - the argument names it already: an identifier of the argument is the name, or ends with it for names of three letters and more
     *   (`name`, `user.Name`, `userName` for `name`; `reqCtx` for `ctx`);
     * - the function has one parameter and its own name says it (`SetName("x")`, `strconv.Itoa(i)`), or that parameter has a one-letter
     *   name (`strings.ToLower(s)`, `fmt.Println(a...)`).
     */
    fun parameterLabel(paramName: String?, argumentText: String, calleeName: String?, paramCount: Int, variadic: Boolean): String? {
        if (paramName.isNullOrEmpty() || paramName == "_") return null
        if (namesParameter(argumentText, paramName)) return null
        if (paramCount == 1 && (paramName.length == 1 || calleeName?.contains(paramName, ignoreCase = true) == true)) return null
        return paramName + (if (variadic) "..." else "") + ":"
    }

    fun namesParameter(argumentText: String, paramName: String): Boolean = WORD.findAll(argumentText).any { word ->
        word.value.equals(paramName, ignoreCase = true) || (paramName.length >= 3 && word.value.endsWith(paramName, ignoreCase = true))
    }

    /** `= 1`, `= 1, 2`; long texts are cut at [MAX_VALUE_CHARS]. */
    fun constantsText(values: List<GoConstant>): String {
        val text = "= " + values.joinToString(", ") { GoDocSignature.constantText(it) }
        return if (text.length <= MAX_VALUE_CHARS) text else text.take(MAX_VALUE_CHARS - 1) + "…"
    }

    /** The GOARCH values where [GoSizes] (the gc layout with 8-byte words) is the layout of the build. */
    val ARCH_64 = setOf("amd64", "arm64", "arm64be", "loong64", "mips64", "mips64le", "mips64p32", "ppc64", "ppc64le", "riscv64", "s390x", "sparc64", "wasm")

    /** The layout of a struct as gc lays it out on 64 bits: [size], how much of it is [padding], and the size with the fields reordered. */
    data class StructLayout(val size: Long, val padding: Long, val optimalSize: Long) {
        val text: String get() = "$size bytes" + (if (padding > 0) ", $padding padding" else "") + (if (optimalSize < size) " ($optimalSize if reordered)" else "")
    }

    private class Group(val size: Long, val align: Long)

    /**
     * The layout of [struct]; null for an empty struct or when the size of a field is unknown (a type parameter, a type that does not
     * resolve). The reordered size is the one the Reorder Fields intention of the host reaches: the names of one declaration (`X, Y int`)
     * move together, zero-size fields go first, then by alignment and size, as `fieldalignment` orders.
     */
    fun structLayout(struct: GoStructType): StructLayout? {
        if (struct.fields.isEmpty()) return null
        val size = GoSizes.sizeof(struct) ?: return null
        var data = 0L
        val groups = ArrayList<Group>()
        var previous: Any? = null
        var count = 0
        var fieldSize = 0L
        var fieldAlign = 1L
        fun flush() { if (count > 0) groups += Group(fieldSize * count, fieldAlign) }
        for (field in struct.fields) {
            val s = GoSizes.sizeof(field.type) ?: return null
            val a = GoSizes.alignof(field.type) ?: return null
            data += s
            // the field definitions of one declaration share their parent; an embedded field is a declaration of its own
            val owner = field.declaration?.parent?.takeIf { !field.embedded }
            if (owner != null && owner === previous && s == fieldSize && a == fieldAlign) { count++; continue }
            flush()
            previous = owner ?: Any()
            count = 1
            fieldSize = s
            fieldAlign = a
        }
        flush()
        val ordered = groups.sortedWith(compareBy<Group> { it.size != 0L }.thenByDescending { it.align }.thenByDescending { it.size })
        return StructLayout(size, size - data, minOf(size, layoutSize(ordered)))
    }

    private fun layoutSize(groups: List<Group>): Long {
        var offset = 0L
        var align = 1L
        for (g in groups) {
            offset = roundUp(offset, g.align) + g.size
            align = maxOf(align, g.align)
        }
        // a trailing zero-size field would let a pointer to it point past the struct: gc pads it
        if (offset > 0 && groups.last().size == 0L) offset++
        return roundUp(offset, align)
    }

    private fun roundUp(x: Long, a: Long): Long = (x + a - 1) / a * a
}

/**
 * Types as the hints print them: a type of another package with the name [file] imports it by (`http.Request`, `*pb.Msg` for an aliased
 * import, the package name when not imported), types of the own package and of the universe bare, the empty interface as `any`.
 * Null for a type that is not known in full: a hint does not guess.
 */
class GoHintTypes(private val file: GoFile) {
    private val ownPath: String? by lazy { GoPackageModel.getInstance(file.project).packagePathOf(file) }
    private val importNames: Map<String, String> by lazy {
        file.imports.filter { !it.isBlank }.associate { it.path to (if (it.isDot) "" else GoScopes.importName(it)) }
    }

    fun render(type: GoType): String? {
        val text = GoTypeRenderer.render(type) { qualifier(it) }
        // `?` is how the renderer prints an unknown type
        if ('?' in text) return null
        return text.replace("interface{}", "any")
    }

    private fun qualifier(named: GoNamedType): String? {
        val path = named.pkgPath ?: return null
        if (path == ownPath || GoUniverse.isBuiltinDeclaration(named.declaration)) return null
        importNames[path]?.let { return it.ifEmpty { null } }
        return (named.declaration.containingFile as? GoFile)?.packageName ?: path.substringAfterLast('/')
    }
}
