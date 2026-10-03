package io.github.golangsupport.lang

import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType

/**
 * The rules of the catalogue past the first batch that follow from the method being written or the sort being called: `Error()`
 * (B6, G5), `Len()` (B9), `String()` of an enumeration (G4), the comparison of `sort.Slice` (C18) and `slices.SortFunc` (C19).
 */
object GoInlineMethods {
    /** The method around the slot when it is [name] with no parameters and one result, and its receiver (named) with its type. */
    private fun method(p: GoInlinePlace, name: String): Pair<String, GoType>? {
        val method = p.owner as? GoMethodDeclaration ?: return null
        if (method.name != name) return null
        val signature = p.typeOf(method) as? GoSignatureType ?: return null
        if (signature.params.isNotEmpty() || signature.results.size != 1) return null
        val receiver = method.receiver ?: return null
        val receiverName = receiver.name?.takeIf { it != "_" } ?: return null
        return receiverName to p.typeOf(receiver)
    }

    // --- Error() (B6, G5) ---

    private val MESSAGE_NAMES = setOf("msg", "message", "text", "reason", "desc", "description", "s", "str", "what")

    /** In `Error() string`: `e.msg`, or `fmt.Sprintf("%s: %v", e.msg, e.err)` when the type wraps an error too. */
    fun b6ErrorMessage(p: GoInlinePlace): String? {
        val (receiver, type) = method(p, "Error") ?: return null
        val struct = p.structOf(type) ?: return null
        val fields = struct.fields.filter { !it.embedded }
        val message = fields.filter { it.name.lowercase() in MESSAGE_NAMES && (it.type.underlying() as? GoBasicType)?.kind?.isString == true }.singleOrNull() ?: return null
        val wrapped = fields.filter { GoReturnValues.isError(it.type) }
        return when (wrapped.size) {
            0 -> "$receiver.${message.name}"
            1 -> "${p.qualifier("fmt")}.Sprintf(\"%s: %v\", $receiver.${message.name}, $receiver.${wrapped[0].name})"
            else -> null
        }
    }

    /** The first line of `Error() string`: `return e.msg`. */
    fun g5ErrorBody(p: GoInlinePlace): String? = b6ErrorMessage(p)?.let { "return $it" }

    // --- Len() (B9) ---

    /** In `Len() int` of a slice or map type (`sort.Interface`): `len(s)`. */
    fun b9Len(p: GoInlinePlace): String? {
        val (receiver, type) = method(p, "Len") ?: return null
        val base = if (type is GoPointerType) type.elem else type
        if (p.elementOf(base) == null && base.underlying() !is GoMapType) return null
        return if (type is GoPointerType) "len(*$receiver)" else "len($receiver)"
    }

    // --- String() of an enumeration (G4) ---

    /**
     * `func (c Color) String() string {` of a type with its constants: the `switch` over them, each returning its name, as `stringer`
     * names them, and the number for the rest. Only when every constant has a value of its own (an `iota` run, distinct literals).
     */
    fun g4EnumString(p: GoInlinePlace): String? {
        val (receiver, type) = method(p, "String") ?: return null
        val named = type as? GoNamedType ?: return null
        if ((named.underlying() as? GoBasicType)?.kind?.isInteger != true) return null
        val constants = GoInlineFlow.constantsOf(p, named).ifEmpty { return null }
        val specs = constants.mapNotNull { p.packageConsts[it]?.parent as? GoConstSpec }.distinct()
        // `Default = Red` would be a second case of the same value
        val values = specs.flatMap { it.expressionList.map { e -> e.text } }
        if (values.any { !it.contains("iota") && !Regex("""^-?\d+$""").matches(it) } || values.size != values.distinct().size) return null
        val indent = p.blockIndent
        val cases = constants.joinToString("") { "\n${indent}case $it:\n$indent${p.unit}return \"$it\"" }
        val typeName = named.name
        return "switch $receiver {$cases\n$indent}\n${indent}return ${p.qualifier("fmt")}.Sprintf(\"$typeName(%d)\", int($receiver))"
    }

    // --- sorting (C18, C19) ---

    private val SORT_FIELDS = listOf("ID", "Id", "Name", "Key", "Title", "Order", "Priority", "Index")

    /** The field a slice of [element] is sorted by: the first of [SORT_FIELDS] it has of an ordered type; "" for an ordered element. */
    private fun sortKey(p: GoInlinePlace, element: GoType): String? {
        if ((element.underlying() as? GoBasicType)?.kind?.isOrdered == true) return ""
        val struct = p.structOf(element) ?: return null
        val field = SORT_FIELDS.firstNotNullOfOrNull { name -> struct.fields.firstOrNull { !it.embedded && it.name == name } } ?: return null
        return if ((field.type.underlying() as? GoBasicType)?.kind?.isOrdered == true) ".${field.name}" else null
    }

    /** The slice and its element of a sorting call `pkg.Function(s, |` with the package at [path]. */
    private fun sorted(p: GoInlinePlace, call: GoCallExpr, index: Int, path: String, function: String): Pair<String, GoType>? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier?.text != function || index != 1 || call.arguments.size != 2) return null
        val qualifier = callee.expression?.text ?: return null
        val imported = GoReturnValues.importName(p.original, path)
        if (imported != qualifier && !(imported == null && qualifier == path.substringAfterLast('/'))) return null
        p.qualifier(path)
        val slice = call.arguments[0] as? GoReferenceExpression ?: return null
        val element = p.elementOf(p.typeOf(slice)) ?: return null
        return slice.text to element
    }

    private fun close(p: GoInlinePlace): String = if (p.slot.closer.isNotEmpty()) ")" else ""

    /** `sort.Slice(users, |`: `func(i, j int) bool { return users[i].ID < users[j].ID }`. */
    fun c18SortSlice(p: GoInlinePlace, call: GoCallExpr, index: Int): String? {
        val (slice, element) = sorted(p, call, index, "sort", "Slice") ?: return null
        val key = sortKey(p, element) ?: return null
        return "func(i, j int) bool { return $slice[i]$key < $slice[j]$key }${close(p)}"
    }

    /** `slices.SortFunc(users, |`: `func(a, b *User) int { return cmp.Compare(a.ID, b.ID) }`. */
    fun c19SortFunc(p: GoInlinePlace, call: GoCallExpr, index: Int): String? {
        val (_, element) = sorted(p, call, index, "slices", "SortFunc") ?: return null
        val key = sortKey(p, element) ?: return null
        val type = p.typeText(element) ?: return null
        return "func(a, b $type) int { return ${p.qualifier("cmp")}.Compare(a$key, b$key) }${close(p)}"
    }
}
