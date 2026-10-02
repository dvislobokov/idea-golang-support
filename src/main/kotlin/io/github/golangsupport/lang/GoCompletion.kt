package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.settings.GoSettings

/**
 * The order of the completion list. gopls matches fuzzily and ranks by a score of its own: `return ni` gives `net.IP`, `net.IPAddr`,
 * `net.IPConn` and only then `nil` (seen live), and the platform keeps the order of the server. A name that begins with what is typed
 * goes first, as in GoLand; among equals the order of the server stays.
 */
object GoCompletionOrder {
    const val RETURN_VALUES = 1000.0

    /** Added to the priority of a value of the type the code wants: above the others that begin the same way, below a better beginning. */
    const val FITS = 0.5

    /** The identifier that ends at [offset]: what the completion is asked for. */
    fun typed(text: CharSequence, offset: Int): String {
        var start = offset.coerceIn(0, text.length)
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return text.subSequence(start, offset.coerceIn(start, text.length)).toString()
    }

    fun priority(typed: String, name: String): Double = when {
        typed.isEmpty() -> 0.0
        name.startsWith(typed) -> 2.0
        name.startsWith(typed, ignoreCase = true) -> 1.0
        else -> 0.0
    }
}

/**
 * The snippets of gopls, made ready for the platform. A snippet of the protocol escapes `}` and `\` with a backslash, and gopls does:
 * the function it offers where one is expected is `func(i, j int) bool {$0\}` (checked with its answer). The converter of the
 * platform leaves the backslash in the code (seen live: `{\` and the brace on the next line), so it is taken away here. Inside a
 * placeholder (`${1:...}`) the text is left as it is: there the brace would end the placeholder.
 */
object GoSnippets {
    fun unescape(snippet: String): String {
        if ('\\' !in snippet) return snippet
        val result = StringBuilder(snippet.length)
        var depth = 0
        var i = 0
        while (i < snippet.length) {
            val c = snippet[i]
            val next = snippet.getOrNull(i + 1)
            when {
                c == '\\' && depth == 0 && (next == '}' || next == '\\') -> {
                    result.append(next)
                    i++
                }
                // an escaped character of a placeholder, and `\$` anywhere: a dollar without it would begin a variable
                c == '\\' && next != null -> {
                    result.append(c).append(next)
                    i++
                }
                c == '$' && next == '{' -> {
                    depth++
                    result.append("\${")
                    i++
                }
                c == '}' && depth > 0 -> {
                    depth--
                    result.append(c)
                }
                else -> result.append(c)
            }
            i++
        }
        return result.toString()
    }
}

/**
 * `return` inside a function with several results: all of its values as one item, `nil, err`. From the PSI ([GoReturnValues]: variables
 * of the result types in scope, zero values, and the error wrapped with `fmt.Errorf` as a second item where `fmt` is imported); the text
 * version ([GoIdioms.returnValues]) where the PSI cannot tell. Works without gopls.
 */
class GoReturnCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is GoFile || !GoSettings.getInstance().completeReturnValues) return
        val text = parameters.editor.document.immutableCharSequence
        if (!GoIdioms.typingReturn(text, parameters.offset)) return
        val psi = try {
            GoReturnValues.forReturn(parameters.position)
        } catch (_: IndexNotReadyException) {
            null
        }
        val values = if (psi == null) listOfNotNull(GoIdioms.returnValues(text, parameters.offset)) else listOfNotNull(psi.plain.takeIf { it.isNotEmpty() }, psi.wrapped)
        val matching = result.withPrefixMatcher(GoPrefixMatcher(GoCompletionOrder.typed(text, parameters.offset)))
        values.forEachIndexed { i, value ->
            val item = LookupElementBuilder.create(value).bold().withIcon(AllIcons.Actions.StepOut).withTypeText("return values", true)
            matching.addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.RETURN_VALUES - i))
        }
    }
}

/**
 * The first argument of `make(`: the slice, map or channel type the place expects (the variable assigned, the parameter, the result),
 * and for a slice or a map the same with the capacity of a slice, string or map in scope (`[]T, 0, len(names)`). PSI only: nothing where
 * the type is not known.
 */
class GoMakeCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is GoFile) return
        val values = try {
            arguments(parameters.position)
        } catch (_: IndexNotReadyException) {
            null
        } ?: return
        val text = parameters.editor.document.immutableCharSequence
        val matching = result.withPrefixMatcher(GoPrefixMatcher(GoCompletionOrder.typed(text, parameters.offset)))
        values.forEachIndexed { i, value ->
            val item = LookupElementBuilder.create(value).bold().withIcon(AllIcons.Nodes.Type).withTypeText("make", true)
            matching.addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.RETURN_VALUES - i))
        }
    }

    companion object {
        /** The items for the reference being completed at [position] when it is the first argument of `make`; null elsewhere. */
        fun arguments(position: PsiElement): List<String>? {
            val reference = position.parent as? GoReferenceExpression ?: return null
            if (reference.expression != null) return null
            val call = (reference.parent as? GoArgumentList)?.parent as? GoCallExpr ?: return null
            val callee = call.expression as? GoReferenceExpression ?: return null
            if (callee.expression != null || callee.identifier?.text != "make") return null
            val arguments = GoPsiUtil.run { call.arguments }
            if (arguments.firstOrNull() !== reference) return null
            val file = call.containingFile as? GoFile ?: return null
            if (DumbService.isDumb(file.project)) return null
            val service = GoSemanticService.getInstance(file.project)
            val type = service.expectedTypeAt(call) ?: return null
            val written = GoReturnValues.typeText(type, file)
            // what the statement assigns is not the size of what it makes
            val assigned = PsiTreeUtil.getParentOfType(call, GoStatement::class.java)?.let { statement ->
                PsiTreeUtil.findChildrenOfType(statement, GoReferenceExpression::class.java).filter { it.textRange.endOffset <= call.textRange.startOffset }.map { it.text }.toSet()
            }.orEmpty()
            val sized = { kind: (GoType) -> Boolean ->
                // a size from something in scope, only while nothing follows the type yet
                if (arguments.size > 1) null
                else GoReturnValues.localVariables(call).firstOrNull { it.name !in assigned && kind(service.declarationType(it).underlying()) }?.name
            }
            return when (type.underlying()) {
                is GoChanType -> listOf(written)
                is GoSliceType -> listOfNotNull(written, sized { it is GoSliceType || it is GoArrayType || (it is GoBasicType && it.kind.isString) }?.let { "$written, 0, len($it)" })
                is GoMapType -> listOfNotNull(written, sized { it is GoSliceType || it is GoArrayType || it is GoMapType }?.let { "$written, len($it)" })
                else -> null
            }
        }
    }
}

/**
 * `<-ch` at the end of a statement line, or `v := <-ch`: the comma-ok receive, `v, ok := <-ch` (and `v, ok = <-ch` where both exist), which
 * tells a closed channel from a zero value. Only for a channel that can be received from (the type from the PSI).
 */
class GoChannelReceiveCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? GoFile ?: return
        val text = parameters.editor.document.immutableCharSequence
        val values = try {
            receives(file, text, parameters.offset)
        } catch (_: IndexNotReadyException) {
            null
        } ?: return
        val matching = result.withPrefixMatcher(PlainPrefixMatcher(""))
        for (value in values) {
            val item = LookupElementBuilder.create(value).bold().withIcon(AllIcons.Actions.Download).withTypeText("receive", true).withInsertHandler { context, _ ->
                // the whole statement is the item: from the first character of the line to what the lookup has inserted
                val document = context.document
                val lineStart = document.getLineStartOffset(document.getLineNumber(context.startOffset))
                val chars = document.charsSequence
                var start = lineStart
                while (start < context.startOffset && (chars[start] == ' ' || chars[start] == '\t')) start++
                document.replaceString(start, context.tailOffset, value)
                context.editor.caretModel.moveToOffset(start + value.length)
            }
            matching.addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.RETURN_VALUES))
        }
    }

    companion object {
        private val RECEIVE = Regex("""^[ \t]*(?:(\w+)[ \t]*:?=[ \t]*)?<-[ \t]*([\w.]+)$""")

        /** The receive statements for the line of [offset] of [file] (the original file, committed), or null. */
        fun receives(file: GoFile, text: CharSequence, offset: Int): List<String>? {
            if (offset <= 0 || offset > text.length) return null
            var lineStart = offset
            while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
            var lineEnd = offset
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            if (text.subSequence(offset, lineEnd).isNotBlank()) return null
            val match = RECEIVE.matchEntire(text.subSequence(lineStart, offset)) ?: return null
            val (value, channel) = match.destructured
            if (DumbService.isDumb(file.project)) return null
            var reference: GoReferenceExpression? = PsiTreeUtil.getParentOfType(file.findElementAt(offset - 1), GoReferenceExpression::class.java)
            while (reference?.parent is GoReferenceExpression && reference.parent.textRange.endOffset == offset) reference = reference.parent as GoReferenceExpression
            if (reference == null || reference.text != channel) return null
            val type = GoSemanticService.getInstance(file.project).typeOf(reference).underlying() as? GoChanType ?: return null
            if (type.dir == GoChanDir.SEND) return null
            val name = value.ifEmpty { "v" }
            val inScope = GoReturnValues.localVariables(reference).mapNotNull { it.name }.toSet()
            return listOfNotNull("$name, ok := <-$channel", "$name, ok = <-$channel".takeIf { name in inScope && "ok" in inScope })
        }
    }
}
