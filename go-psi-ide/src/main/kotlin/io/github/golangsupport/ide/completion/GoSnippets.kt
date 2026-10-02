package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import io.github.golangsupport.ide.intentions.GoZeroValues
import io.github.golangsupport.semantic.types.*

/**
 * Statement idioms offered at a statement start inside a function body (rule-based, see
 * docs/ML.md: these cover what a small inline model would mostly predict):
 *
 * - `iferr`: `if err != nil { return <zero values>, err }` with the zero values of the enclosing
 *   function's results (`0`, `""`, `false`, `nil`, `T{}`, `*new(T)` for type parameters) and `err`
 *   for results of type `error`;
 * - `for range xs`: `for i, v := range xs {}` (`k, v` for maps, `i, r` for strings, `v` for
 *   channels) for each visible local or parameter of a slice, array, map, string or channel type;
 * - `switch {}` / `select {}` skeletons.
 */
object GoSnippets {

    private const val CARET = "\u0000"

    fun collect(context: GoCompletionContext, scope: List<GoCandidate>, out: MutableList<GoCandidate>) {
        if (context.kind != GoCompletionContext.Kind.STATEMENT || context.functionOwner == null) return
        out += snippet("iferr", "if err != nil {...}", iferrText(context))
        for (candidate in scope) {
            if (candidate.kind != GoCandidateKind.LOCAL && candidate.kind != GoCandidateKind.PARAMETER) continue
            val type = candidate.valueType ?: continue
            val vars = rangeVariables(type) ?: continue
            val header = if (vars.isEmpty()) "for range ${candidate.name}" else "for $vars := range ${candidate.name}"
            out += snippet("for range ${candidate.name}", "$header {...}", "$header {\n\t$CARET\n}")
        }
        out += snippet("switch {}", "switch {...}", "switch $CARET {\ncase :\n}")
        out += snippet("select {}", "select {...}", "select {\ncase $CARET:\n}")
    }

    private fun snippet(lookup: String, presentable: String, text: String): GoCandidate =
        GoCandidate(
            lookup, GoCandidateKind.SNIPPET, GoScopeLevel.KEYWORD, lookupString = lookup, presentableText = lookup,
            tailText = "  $presentable".takeIf { presentable != lookup }, insertHandler = handler(text),
        )

    /** Replaces the lookup string with [template] (lines after the first indented like the current line). */
    private fun handler(template: String): InsertHandler<LookupElement> = InsertHandler { ctx, _ ->
        val document = ctx.document
        val start = ctx.startOffset
        val lineStart = document.getLineStartOffset(document.getLineNumber(start))
        val indent = document.charsSequence.subSequence(lineStart, start).takeWhile { it == ' ' || it == '\t' }.toString()
        val text = template.replace("\n", "\n$indent")
        val caret = text.indexOf(CARET)
        val clean = text.replace(CARET, "")
        document.replaceString(start, ctx.tailOffset, clean)
        ctx.editor.caretModel.moveToOffset(start + if (caret >= 0) caret else clean.length)
    }

    /** `if err != nil {\n\treturn ..., err\n}`; results are known from the enclosing signature. */
    fun iferrText(context: GoCompletionContext): String {
        val signature = context.semantics.enclosingSignature
        val values = signature?.results?.map { zeroValue(it.type, context) }.orEmpty()
        val ret = if (values.isEmpty()) "return" else "return " + values.joinToString(", ")
        return "if err != nil {\n\t$ret\n}$CARET"
    }

    /** The zero value of [type] as Go source; `err` for `error`. */
    fun zeroValue(type: GoType, context: GoCompletionContext): String =
        if (GoZeroValues.isError(type)) "err" else GoZeroValues.of(type) { typeSource(it, context) }

    /** The type as written in the current package: other packages' named types are qualified by their package name. */
    private fun typeSource(type: GoType, context: GoCompletionContext): String {
        if (type is GoNamedType) {
            val path = type.pkgPath
            val own = context.semantics.packagePath
            val qualifier = if (path != null && own != null && path != own) {
                context.semantics.imports.firstOrNull { it.path == path }?.let { context.semantics.importName(it) } ?: path.substringAfterLast('/')
            } else null
            val args = if (type.typeArgs.isEmpty()) "" else type.typeArgs.joinToString(", ", "[", "]") { typeSource(it, context) }
            return (qualifier?.let { "$it." } ?: "") + type.name + args
        }
        return GoLookupElementFactory.typeText(type)
    }

    /** The variables a `for range` over [type] declares ("" for range-over-int without variables never happens). */
    private fun rangeVariables(type: GoType): String? = when (val u = type.underlying()) {
        is GoSliceType, is GoArrayType -> "i, v"
        is GoPointerType -> if (u.elem.underlying() is GoArrayType) "i, v" else null
        is GoMapType -> "k, v"
        is GoChanType -> if (u.dir != GoChanDir.SEND) "v" else null
        // Integers (`for i := range n`) are not offered: every int variable would get a snippet.
        is GoBasicType -> if (u.kind.isString) "i, r" else null
        else -> null
    }
}
