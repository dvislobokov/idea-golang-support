package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Values that have a name: an HTTP status typed as a number becomes its constant of `net/http`, `404` → `http.StatusNotFound`, with the
 * import; and the layout of a time, inside the string of `time.Parse` or `t.Format`, is offered as whole layouts (`2006-01-02`) and as its
 * parts (`Jan`, `15`, `Z07:00`), which nobody remembers. Both as GoLand offers them.
 */
object GoHttpStatuses {
    /** The constants of `net/http`, by code. */
    val BY_CODE: Map<Int, String> = linkedMapOf(
        100 to "Continue", 101 to "SwitchingProtocols", 102 to "Processing", 103 to "EarlyHints",
        200 to "OK", 201 to "Created", 202 to "Accepted", 203 to "NonAuthoritativeInfo", 204 to "NoContent", 205 to "ResetContent", 206 to "PartialContent",
        207 to "MultiStatus", 208 to "AlreadyReported", 226 to "IMUsed",
        300 to "MultipleChoices", 301 to "MovedPermanently", 302 to "Found", 303 to "SeeOther", 304 to "NotModified", 305 to "UseProxy",
        307 to "TemporaryRedirect", 308 to "PermanentRedirect",
        400 to "BadRequest", 401 to "Unauthorized", 402 to "PaymentRequired", 403 to "Forbidden", 404 to "NotFound", 405 to "MethodNotAllowed",
        406 to "NotAcceptable", 407 to "ProxyAuthRequired", 408 to "RequestTimeout", 409 to "Conflict", 410 to "Gone", 411 to "LengthRequired",
        412 to "PreconditionFailed", 413 to "RequestEntityTooLarge", 414 to "RequestURITooLong", 415 to "UnsupportedMediaType",
        416 to "RequestedRangeNotSatisfiable", 417 to "ExpectationFailed", 418 to "Teapot", 421 to "MisdirectedRequest", 422 to "UnprocessableEntity",
        423 to "Locked", 424 to "FailedDependency", 425 to "TooEarly", 426 to "UpgradeRequired", 428 to "PreconditionRequired", 429 to "TooManyRequests",
        431 to "RequestHeaderFieldsTooLarge", 451 to "UnavailableForLegalReasons",
        500 to "InternalServerError", 501 to "NotImplemented", 502 to "BadGateway", 503 to "ServiceUnavailable", 504 to "GatewayTimeout",
        505 to "HTTPVersionNotSupported", 506 to "VariantAlsoNegotiates", 507 to "InsufficientStorage", 508 to "LoopDetected", 510 to "NotExtended",
        511 to "NetworkAuthenticationRequired",
    )

    /** Where a number is a status: a call or a comparison that says so on the same line, or a `case` of a switch on one. */
    private val PLACE = Regex("""WriteHeader\s*\(|StatusCode|\.Status\b|http\.Error\s*\(|AbortWithStatus\w*\s*\(|\.(?:JSON|String|Data|HTML|XML|Redirect|Render|Blob|SendStatus|Status)\s*\(|^\s*case\s""")

    /** The codes that begin with [typed], where the line looks like a place for a status; empty for anything else. */
    fun matching(line: String, typed: String): List<Int> {
        if (typed.isEmpty() || typed.length > 3 || !typed.all { it.isDigit() } || !PLACE.containsMatchIn(line)) return emptyList()
        return BY_CODE.keys.filter { it.toString().startsWith(typed) }
    }
}

object GoTimeLayouts {
    class Layout(val text: String, val constant: String?)

    /** Whole layouts, the named ones of `time` first; the caller writes the literal or the constant. */
    val LAYOUTS: List<Layout> = listOf(
        Layout("2006-01-02T15:04:05Z07:00", "time.RFC3339"), Layout("2006-01-02T15:04:05.999999999Z07:00", "time.RFC3339Nano"),
        Layout("2006-01-02 15:04:05", "time.DateTime"), Layout("2006-01-02", "time.DateOnly"), Layout("15:04:05", "time.TimeOnly"),
        Layout("Mon, 02 Jan 2006 15:04:05 MST", "time.RFC1123"), Layout("Mon, 02 Jan 2006 15:04:05 -0700", "time.RFC1123Z"),
        Layout("02 Jan 06 15:04 MST", "time.RFC822"), Layout("Mon Jan _2 15:04:05 2006", "time.ANSIC"), Layout("Jan _2 15:04:05", "time.Stamp"),
        Layout("3:04PM", "time.Kitchen"),
        Layout("02.01.2006", null), Layout("02.01.2006 15:04", null), Layout("02/01/2006", null), Layout("01/02/2006", null), Layout("2006-01-02 15:04", null),
        Layout("20060102", null), Layout("2006-01-02T15:04:05", null), Layout("15:04", null), Layout("January 2, 2006", null), Layout("Jan 2, 2006", null),
    )

    /** The parts of a layout, what each one means. */
    val PARTS: List<Pair<String, String>> = listOf(
        "2006" to "year", "06" to "year, two digits", "01" to "month", "1" to "month, no zero", "Jan" to "month, short", "January" to "month",
        "02" to "day", "2" to "day, no zero", "_2" to "day, space padded", "002" to "day of year", "Mon" to "weekday, short", "Monday" to "weekday",
        "15" to "hour, 24", "03" to "hour, 12", "3" to "hour, 12, no zero", "04" to "minute", "4" to "minute, no zero", "05" to "second", "5" to "second, no zero",
        "PM" to "AM or PM", "pm" to "am or pm", "MST" to "time zone name", "Z07:00" to "offset, Z for UTC", "-07:00" to "offset", "-0700" to "offset, no colon",
        "Z0700" to "offset, Z for UTC, no colon", ".000" to "milliseconds", ".000000" to "microseconds", ".000000000" to "nanoseconds", ".999" to "milliseconds, trimmed",
    )

    private val CALL = Regex("""(?:\btime\.Parse(?:InLocation)?|\.Format|\.AppendFormat\([^,]*,|\btime\.ParseInLocation)\s*\(\s*$""")

    /**
     * Whether the string that begins at [quote] (the offset of its opening quote) is the layout of a call of `time`: the text before it
     * on the line ends with `time.Parse(`, `.Format(` and the like.
     */
    fun isLayoutString(text: CharSequence, quote: Int): Boolean {
        var start = quote
        while (start > 0 && text[start - 1] != '\n') start--
        return CALL.containsMatchIn(text.subSequence(start, quote))
    }

    /** The opening quote of the string [offset] is inside, on its line; null outside strings. Raw strings and escapes are not layouts. */
    fun quoteBefore(text: CharSequence, offset: Int): Int? {
        var i = offset
        var quote: Int? = null
        while (i > 0 && text[i - 1] != '\n') {
            i--
            if (text[i] == '"' && (i == 0 || text[i - 1] != '\\')) {
                if (quote == null) quote = i else return null
            }
        }
        return quote
    }

    /** The part of a layout that is being typed: from the last separator before the caret. */
    fun partTyped(text: CharSequence, quote: Int, offset: Int): String {
        var start = offset
        while (start > quote + 1 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '.')) start--
        return text.subSequence(start, offset).toString()
    }
}

class GoValueCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? GoFile ?: return
        if (!GoSettings.getInstance().completionValues) return
        val text = parameters.editor.document.immutableCharSequence
        val offset = parameters.offset
        val elementType = parameters.position.node.elementType
        if (elementType in GoTokenSets.COMMENTS) return
        if (elementType == GoTypes.STRING) timeLayouts(text, offset, result) else httpStatuses(file, text, offset, result)
    }

    private fun httpStatuses(file: GoFile, text: CharSequence, offset: Int, result: CompletionResultSet) {
        val typed = GoCompletionOrder.typed(text, offset)
        var lineStart = offset - typed.length
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        val codes = GoHttpStatuses.matching(text.subSequence(lineStart, lineEnd).toString(), typed)
        if (codes.isEmpty()) return
        val imports = GoStructure.of(file).imports
        val http = imports.firstOrNull { it.path == "net/http" }
        val qualifier = http?.let(GoImports::nameOf) ?: "http"
        val items = result.withPrefixMatcher(typed)
        for (code in codes) {
            val name = "$qualifier.Status${GoHttpStatuses.BY_CODE[code]}"
            val element = LookupElementBuilder.create(code.toString()).withPresentableText(name).withTypeText(code.toString(), true).withIcon(AllIcons.Nodes.Constant)
                .withInsertHandler { context, _ ->
                    val document = context.document
                    document.replaceString(context.startOffset, context.tailOffset, name)
                    if (http == null) GoImports.add(document.immutableCharSequence, "net/http")?.let { document.insertString(it.offset, it.text) }
                    context.commitDocument()
                }
            items.addElement(PrioritizedLookupElement.withPriority(element, PRIORITY))
        }
    }

    private fun timeLayouts(text: CharSequence, offset: Int, result: CompletionResultSet) {
        val quote = GoTimeLayouts.quoteBefore(text, offset) ?: return
        if (!GoTimeLayouts.isLayoutString(text, quote)) return
        val fromQuote = text.subSequence(quote + 1, offset).toString()
        // a plain prefix: the fuzzy matcher of the platform would find `20` in the middle of `Mon, 02 Jan 2006` (seen in a test)
        val whole = result.withPrefixMatcher(PlainPrefixMatcher(fromQuote, true))
        for (layout in GoTimeLayouts.LAYOUTS) {
            val element = LookupElementBuilder.create(layout.text).withTypeText(layout.constant ?: "layout", true).withIcon(AllIcons.Nodes.Constant)
            whole.addElement(PrioritizedLookupElement.withPriority(element, PRIORITY))
        }
        val part = GoTimeLayouts.partTyped(text, quote, offset)
        val parts = result.withPrefixMatcher(PlainPrefixMatcher(part, true))
        for ((piece, meaning) in GoTimeLayouts.PARTS) {
            parts.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(piece).withTypeText(meaning, true).withIcon(AllIcons.Nodes.Field), PRIORITY - 1))
        }
    }

    private companion object {
        /** Above the keyword templates: a number typed where a status goes wants nothing else. */
        const val PRIORITY = 950.0
    }
}
