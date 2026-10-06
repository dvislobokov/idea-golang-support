package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.completion.GoCompletionContext.Kind
import io.github.golangsupport.ide.inspections.GoTimeLayoutCalls
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTokenSets

/**
 * Time layout elements inside the layout string of `Time.Format`, `Time.AppendFormat`, `time.Parse` and `time.ParseInLocation`
 * ([GoTimeLayoutCalls]), as GoLand offers them (`docs/goland-analysis/dumps/completion.txt`, probe C5): the aliases `YY YYYY MM DD hh mm ss`
 * (shown as typed, inserted as the reference element: `YYYY` writes `2006`) with their description in the type column, then the groups
 * `year... month... day... hour... minute... second... zone...`; picking a group reopens the list with every element of that group.
 */
object GoTimeLayoutCompletion {

    /** One layout element: what the row shows, what it writes, what it means. */
    class Element(val shown: String, val token: String, val description: String)

    /** GoLand's top rows (probe C5), in its order. */
    val ALIASES: List<Element> = listOf(
        Element("YY", "06", "Year (two-digit)"),
        Element("YYYY", "2006", "Year (four-digit)"),
        Element("MM", "01", "Month (zero-padded)"),
        Element("DD", "02", "Day of month (zero-padded)"),
        Element("hh", "15", "24-hour (zero-padded)"),
        Element("mm", "04", "Minute (zero-padded)"),
        Element("ss", "05", "Second (zero-padded)"),
    )

    /** The groups behind `year...` etc.: every reference element of time/format.go, by the field it prints. */
    val GROUPS: Map<String, List<Element>> = linkedMapOf(
        "year" to listOf(
            Element("2006", "2006", "Year (four-digit)"),
            Element("06", "06", "Year (two-digit)"),
        ),
        "month" to listOf(
            Element("January", "January", "Month (full name)"),
            Element("Jan", "Jan", "Month (three-letter)"),
            Element("01", "01", "Month (zero-padded)"),
            Element("1", "1", "Month"),
        ),
        "day" to listOf(
            Element("02", "02", "Day of month (zero-padded)"),
            Element("_2", "_2", "Day of month (space-padded)"),
            Element("2", "2", "Day of month"),
            Element("002", "002", "Day of year (zero-padded)"),
            Element("__2", "__2", "Day of year (space-padded)"),
            Element("Monday", "Monday", "Weekday (full name)"),
            Element("Mon", "Mon", "Weekday (three-letter)"),
        ),
        "hour" to listOf(
            Element("15", "15", "24-hour (zero-padded)"),
            Element("03", "03", "12-hour (zero-padded)"),
            Element("3", "3", "12-hour"),
            Element("PM", "PM", "AM/PM"),
            Element("pm", "pm", "am/pm"),
        ),
        "minute" to listOf(
            Element("04", "04", "Minute (zero-padded)"),
            Element("4", "4", "Minute"),
        ),
        "second" to listOf(
            Element("05", "05", "Second (zero-padded)"),
            Element("5", "5", "Second"),
            Element(".000", ".000", "Milliseconds"),
            Element(".000000", ".000000", "Microseconds"),
            Element(".000000000", ".000000000", "Nanoseconds"),
            Element(".999", ".999", "Milliseconds (trailing zeros dropped)"),
            Element(".999999999", ".999999999", "Nanoseconds (trailing zeros dropped)"),
        ),
        "zone" to listOf(
            Element("MST", "MST", "Time zone abbreviation"),
            Element("Z07:00", "Z07:00", "ISO 8601 offset, Z for UTC"),
            Element("Z0700", "Z0700", "ISO 8601 offset without colon, Z for UTC"),
            Element("-07:00", "-07:00", "Offset ±hh:mm"),
            Element("-0700", "-0700", "Offset ±hhmm"),
            Element("-07", "-07", "Offset ±hh"),
        ),
    )

    /** Row text of a group (`year...`). */
    fun groupRow(group: String): String = "$group..."

    /** The group picked last in [editor], when the caret is still where the pick left it (cleared by reading). */
    private val PICKED_GROUP: Key<Pair<String, Int>> = Key.create("gopsi.completion.timeLayoutGroup")

    /** The letters and digits typed before the caret inside the literal (`YY` of `"2006-YY`, `20`); [before] is the literal's text after the quote. */
    fun prefix(before: String): String = before.takeLastWhile { it.isLetterOrDigit() }

    /** The layout literal holding [leaf] in [file] (the original file: the copy of completion has no package to resolve `time` in), or null. */
    fun layoutLiteral(leaf: PsiElement): GoStringLiteral? {
        if (!GoTokenSets.STRING_LITERALS.contains(leaf.node?.elementType)) return null
        val literal = leaf.parent as? GoStringLiteral ?: return null
        if (literal.parent !is GoArgumentList) return null
        val call = literal.parent.parent as? GoCallExpr ?: return null
        return literal.takeIf { GoTimeLayoutCalls.layoutArgument(call) === literal }
    }

    /** The layout literal at [offset] of the original [file] (the caret may stand before the closing quote). */
    fun layoutLiteralAt(file: PsiFile, offset: Int): GoStringLiteral? =
        (file.findElementAt(offset)?.let(::layoutLiteral)) ?: (if (offset > 0) file.findElementAt(offset - 1)?.let(::layoutLiteral) else null)

    /** The rows for [editor] at [offset]: the group picked there before, else the aliases and the groups. */
    fun elements(editor: Editor, offset: Int): List<LookupElement> {
        val picked = editor.getUserData(PICKED_GROUP)
        editor.putUserData(PICKED_GROUP, null)
        val group = picked?.takeIf { it.second == offset }?.first?.let(GROUPS::get)
        val rows = ArrayList<LookupElement>()
        if (group != null) {
            group.forEachIndexed { i, e -> rows += PrioritizedLookupElement.withPriority(row(e), 100.0 - i) }
            return rows
        }
        ALIASES.forEachIndexed { i, e -> rows += PrioritizedLookupElement.withPriority(row(e), 100.0 - i) }
        GROUPS.keys.forEachIndexed { i, g ->
            val item = LookupElementBuilder.create(groupRow(g)).withInsertHandler(groupHandler(g))
            rows += PrioritizedLookupElement.withPriority(item, 50.0 - i)
        }
        return rows
    }

    /**
     * An alias row is looked up by what it shows (`YYYY`) and writes its element on insertion: the host adds the elements themselves
     * (`2006`, `lang.GoTimeLayouts`), and two rows with one lookup string are merged into one by the platform.
     */
    private fun row(e: Element): LookupElement {
        val item = LookupElementBuilder.create(e.shown).withTypeText(e.description).withCaseSensitivity(true)
        if (e.shown == e.token) return item
        return item.withInsertHandler { ctx, _ ->
            ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, e.token)
            ctx.editor.caretModel.moveToOffset(ctx.startOffset + e.token.length)
        }
    }

    /** Removes the group row and reopens the list with the group's elements. */
    private fun groupHandler(group: String): InsertHandler<LookupElement> = InsertHandler { ctx, _ ->
        ctx.document.deleteString(ctx.startOffset, ctx.tailOffset)
        ctx.editor.caretModel.moveToOffset(ctx.startOffset)
        ctx.editor.putUserData(PICKED_GROUP, group to ctx.startOffset)
        ctx.setLaterRunnable { AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor) }
    }
}

/** Completion of time layout elements in the layout string of the `time` calls (registered by [GoCompletionContributor]). Not dumb-aware: the call is resolved. */
internal class GoTimeLayoutProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, processing: com.intellij.util.ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        if (context.kind != Kind.STRING_ARGUMENT) return
        val leaf = context.leaf
        val inLeaf = parameters.offset - leaf.textRange.startOffset
        if (inLeaf < 1 || inLeaf > leaf.textLength) return
        // `%` directives belong to the Printf verbs.
        val before = leaf.text.substring(1, inLeaf)
        if (GoFormatVerbCompletion.directivePrefix(before) != null) return
        GoTimeLayoutCompletion.layoutLiteralAt(context.originalFile, parameters.offset) ?: return
        // No stopHere: the host adds whole layouts (`2006-01-02`, `lang.GoTimeLayouts`) to the same list.
        result.withPrefixMatcher(GoTimeLayoutCompletion.prefix(before)).addAllElements(GoTimeLayoutCompletion.elements(parameters.editor, parameters.offset))
    }
}
