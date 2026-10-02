package io.github.golangsupport.lang

import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.semantic.infer.GoSizes
import io.github.golangsupport.semantic.types.GoType

/**
 * The layout of a struct as the compiler lays it out on a 64-bit machine: what `fieldalignment` of `go vet` reports, and the order of
 * fields that wastes the least. [analyze] of a [GoStructType] takes the sizes from the types of the PSI ([GoSizes] of go-psi). The text
 * path ([analyze] of a body) knows no types beyond the text: the sizes of the predeclared types, of pointers, slices, maps, channels,
 * functions and interfaces are fixed; a well-known type of the standard library is in a table; a type declared in the same file is
 * followed. Anything else makes the size unknown, and nothing is proposed.
 */
object GoFieldAlignment {
    class Layout(val size: Int, val align: Int)

    /** One entry of the body: the lines of a field (the comments right above it and the trailing one are its own) and its layout. */
    class Field(val lines: List<String>, val layout: Layout)

    class Result(val currentSize: Int, val optimalSize: Int, val fields: List<Field>) {
        val saves: Boolean get() = optimalSize < currentSize
    }

    private val KNOWN: Map<String, Layout> = buildMap {
        for (name in listOf("bool", "int8", "uint8", "byte")) put(name, Layout(1, 1))
        for (name in listOf("int16", "uint16")) put(name, Layout(2, 2))
        for (name in listOf("int32", "uint32", "rune", "float32")) put(name, Layout(4, 4))
        for (name in listOf("int64", "uint64", "float64", "complex64", "int", "uint", "uintptr", "unsafe.Pointer", "time.Duration", "atomic.Int64", "atomic.Uint64", "atomic.Uintptr")) put(name, Layout(8, 8))
        put("complex128", Layout(16, 8))
        for (name in listOf("string", "error", "any", "context.Context", "atomic.Value")) put(name, Layout(16, 8))
        for (name in listOf("atomic.Int32", "atomic.Uint32", "atomic.Bool")) put(name, Layout(4, 4))
        put("time.Time", Layout(24, 8))
        put("sync.Mutex", Layout(8, 4))
        put("sync.RWMutex", Layout(24, 8))
        put("sync.WaitGroup", Layout(16, 8))
        put("sync.Once", Layout(12, 4))
        put("sync.Map", Layout(40, 8))
        for (name in listOf("json.RawMessage", "sql.NullString", "big.Int")) put(name, if (name == "big.Int") Layout(32, 8) else Layout(24, 8))
    }

    /** The layout of a type written as [type]; [local] resolves a name declared in the file to the text of its type, or to null. */
    fun layoutOf(type: String, local: (String) -> String?, depth: Int = 0): Layout? {
        val text = type.trim()
        if (depth > 8 || text.isEmpty()) return null
        KNOWN[text]?.let { return it }
        return when {
            text.startsWith("*") || text.startsWith("chan ") || text.startsWith("chan<-") || text.startsWith("<-chan") || text.startsWith("func(") || text.startsWith("func ") -> Layout(8, 8)
            text.startsWith("map[") -> Layout(8, 8)
            text.startsWith("[]") -> Layout(24, 8)
            text == "struct{}" || text == "struct {}" -> Layout(0, 1)
            text == "interface{}" || text == "interface {}" -> Layout(16, 8)
            text.startsWith("[") -> {
                val close = text.indexOf(']')
                val count = text.substring(1, close).trim().toIntOrNull() ?: return null
                val element = layoutOf(text.substring(close + 1), local, depth + 1) ?: return null
                Layout(count * element.size, element.align)
            }
            text.startsWith("atomic.Pointer[") -> Layout(8, 8)
            text.all { it.isLetterOrDigit() || it == '_' } -> local(text)?.let { layoutOf(it, local, depth + 1) }
            else -> null
        }
    }

    /** The layout of a struct whose fields have [layouts], in that order: each field at its alignment, the whole rounded to the largest. */
    fun structLayout(layouts: List<Layout>): Layout {
        var offset = 0
        var align = 1
        for (layout in layouts) {
            offset = roundUp(offset, layout.align)
            offset += layout.size
            align = maxOf(align, layout.align)
        }
        // a trailing zero-size field would let a pointer to it point past the struct: the compiler pads it (types.Sizes of go/types)
        if (offset > 0 && layouts.last().size == 0) offset++
        return Layout(roundUp(offset, align), align)
    }

    private fun roundUp(offset: Int, align: Int): Int = (offset + align - 1) / align * align

    /**
     * The body of a struct between its braces: the fields with their lines, laid out as they are and as they would be at best. Null when a
     * field is not on a line of its own (a struct literal type over several lines), or the size of a type is unknown.
     */
    fun analyze(body: CharSequence, local: (String) -> String?): Result? {
        val entries = split(body) ?: return null
        val fields = entries.map { entry ->
            val (fieldType, count) = typeOf(entry.field) ?: return null
            val layout = layoutOf(fieldType, local) ?: return null
            Field(entry.lines, Layout(layout.size * count, layout.align))
        }
        return resultOf(fields)
    }

    /**
     * The same for a struct of the PSI, the sizes from the types of its fields as the type checker of go-psi knows them ([GoSizes]: the
     * layout of gc on 64 bits), a type of any package or a generic instance included. Null when a field is not on a line of its own or
     * the size of a type is unknown (a type parameter, a type that does not resolve). Needs read access.
     */
    fun analyze(struct: GoStructType): Result? {
        val close = struct.rbrace ?: return null
        val text = struct.text
        val open = struct.lbrace ?: return null
        val body = text.substring(open.startOffsetInParent + 1, close.startOffsetInParent)
        val entries = split(body) ?: return null
        val declarations = struct.fieldDeclarationList
        if (entries.size != declarations.size) return null
        val fields = entries.zip(declarations).map { (entry, declaration) ->
            val names = GoStructPsi.fieldsOf(declaration)
            val layout = layoutOf(names.firstOrNull()?.type ?: return null) ?: return null
            Field(entry.lines, Layout(layout.size * names.size, layout.align))
        }
        return resultOf(fields)
    }

    /** The layout of [type] by [GoSizes]; null when it is not known or does not fit an Int. */
    fun layoutOf(type: GoType): Layout? {
        val size = GoSizes.sizeof(type)?.takeIf { it <= Int.MAX_VALUE } ?: return null
        val align = GoSizes.alignof(type) ?: return null
        return Layout(size.toInt(), align.toInt())
    }

    private fun resultOf(fields: List<Field>): Result? {
        if (fields.isEmpty()) return null
        val current = structLayout(fields.map { it.layout }).size
        val ordered = optimalOrder(fields)
        return Result(current, structLayout(ordered.map { it.layout }).size, ordered)
    }

    /** As `fieldalignment` orders: zero-size fields first (a trailing one costs padding), then the most aligned, then the largest; ties keep their order. */
    fun optimalOrder(fields: List<Field>): List<Field> =
        fields.sortedWith(compareBy<Field> { it.layout.size != 0 }.thenByDescending { it.layout.align }.thenByDescending { it.layout.size })

    /** The text between the braces, with the fields in the order of [result]; the indent and the line ends of the body are kept. */
    fun rewrite(body: CharSequence, result: Result): String {
        val text = body.toString()
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val indent = text.lines().firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
        // the last line of the body is the indent of the closing brace
        val closing = text.substringAfterLast('\n').takeIf { it.isBlank() }.orEmpty()
        return newline + result.fields.flatMap { it.lines }.joinToString(newline) { indent + it } + newline + closing
    }

    private class Entry(val lines: List<String>, val field: String)

    /** The lines of the body as fields: the comment lines above a field go with it; blank lines are dropped (the order is new anyway). */
    private fun split(body: CharSequence): List<Entry>? {
        val result = ArrayList<Entry>()
        var pending = ArrayList<String>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("//")) { pending.add(line); continue }
            if (line.startsWith("/*")) return null
            // a field over several lines (an inline struct, a function type with its parameters wrapped) is not something to move around
            if (depth(line) != 0) return null
            result += Entry(pending + line, line)
            pending = ArrayList()
        }
        if (pending.isNotEmpty()) {
            if (result.isEmpty()) return null
            val last = result.removeAt(result.size - 1)
            result += Entry(last.lines + pending, last.field)
        }
        return result
    }

    private fun depth(line: String): Int {
        var depth = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' || c == '`' -> { i = line.indexOf(c, i + 1).takeIf { it >= 0 } ?: return depth; }
                c == '/' && line.getOrNull(i + 1) == '/' -> return depth
                c == '{' || c == '(' || c == '[' -> depth++
                c == '}' || c == ')' || c == ']' -> depth--
            }
            i++
        }
        return depth
    }

    /** `a, b int `json:"x"` // c` -> `int` and 2; an embedded `*pkg.T` -> `*pkg.T` and 1. */
    private fun typeOf(field: String): Pair<String, Int>? {
        var line = field
        line.indexOf("//").takeIf { it >= 0 }?.let { line = line.substring(0, it) }
        line.indexOf('`').takeIf { it >= 0 }?.let { line = line.substring(0, it) }
        val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        // an embedded field is its type alone
        if (tokens.size == 1) return tokens[0] to 1
        // `a, b int`, `a,b int`: the names end with the first token without a comma after it
        var typeStart = 0
        while (typeStart < tokens.size - 1 && tokens[typeStart].endsWith(",")) typeStart++
        val names = tokens.subList(0, typeStart + 1).flatMap { it.split(',') }.filter { it.isNotEmpty() }
        if (typeStart + 1 >= tokens.size || !names.all { name -> name.all { c -> c.isLetterOrDigit() || c == '_' } }) return null
        return tokens.subList(typeStart + 1, tokens.size).joinToString(" ") to names.size
    }

    private val WHITESPACE = Regex("""\s+""")

    /** What the file itself says a type name is: the type of a plain `type X int64`, or the struct written as `struct { ... }` of a struct type. */
    fun localTypes(structure: GoFileStructure, text: CharSequence): (String) -> String? = { name ->
        structure.declarations.firstOrNull { it.kind.isType && it.name == name }?.let { declaration ->
            when (declaration.kind) {
                GoDeclarationKind.STRUCT -> declaration.body?.let { body -> structSize(text.subSequence(body.startOffset + 1, body.endOffset - 1), structure, text) }
                GoDeclarationKind.TYPE -> declaration.signature?.removePrefix("=")?.trim()
                else -> null
            }
        }
    }

    /** A local struct type as a fixed-size array of bytes is not right (the alignment): it is written as `[N]` of a unit of its alignment instead. */
    private fun structSize(body: CharSequence, structure: GoFileStructure, text: CharSequence): String? {
        val entries = split(body) ?: return null
        val layouts = entries.map { entry -> typeOf(entry.field)?.let { (type, count) -> layoutOf(type, localTypes(structure, text))?.let { Layout(it.size * count, it.align) } } ?: return null }
        if (layouts.isEmpty()) return "struct{}"
        val layout = structLayout(layouts)
        val unit = when (layout.align) { 1 -> "byte"; 2 -> "int16"; 4 -> "int32"; else -> "int64" }
        return "[${layout.size / layout.align}]$unit"
    }
}
