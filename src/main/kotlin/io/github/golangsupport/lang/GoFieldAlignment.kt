package io.github.golangsupport.lang

import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.semantic.infer.GoSizes
import io.github.golangsupport.semantic.types.GoType

/**
 * The layout of a struct as the compiler lays it out on a 64-bit machine: what `fieldalignment` of `go vet` reports, and the order of
 * fields that wastes the least. [analyze] of a [GoStructType] takes the sizes from the types of the PSI ([GoSizes] of go-psi); a type
 * whose size is unknown makes nothing to propose.
 */
object GoFieldAlignment {
    class Layout(val size: Int, val align: Int)

    /** One entry of the body: the lines of a field (the comments right above it and the trailing one are its own) and its layout. */
    class Field(val lines: List<String>, val layout: Layout)

    class Result(val currentSize: Int, val optimalSize: Int, val fields: List<Field>) {
        val saves: Boolean get() = optimalSize < currentSize
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
     * The fields of a struct of the PSI with their lines, laid out as they are and as they would be at best; the sizes from the types of
     * its fields as the type checker of go-psi knows them ([GoSizes]: the layout of gc on 64 bits), a type of any package or a generic
     * instance included. Null when a field is not on a line of its own or the size of a type is unknown (a type parameter, a type that does not resolve). Needs read access.
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
}
