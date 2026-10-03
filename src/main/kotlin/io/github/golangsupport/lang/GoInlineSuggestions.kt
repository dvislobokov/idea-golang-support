package io.github.golangsupport.lang

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFile

/**
 * Grey text from the context of the caret (Tab to accept), the catalogue of `docs/INLINE-SUGGESTIONS.md`: the right side of `x :=`,
 * the values of `return`, the arguments of a call, the fields of a literal, what to range over, the condition after `v, ok :=`, the
 * first line of a body. Rules over the PSI and the types of go-psi, no guessing: one suggestion and only when a rule is sure of it;
 * two equal candidates give nothing.
 *
 * The suggestion is asked right after a keystroke, before the document is committed, and the line being typed rarely parses
 * (`arr := ` takes the next line as its value). So the rules look at a copy of the file made from the text of the document, with
 * the slot filled by a [PLACEHOLDER] identifier (and a closing bracket or an empty block where the line needs one): the statement
 * is whole, what is below it parses as it is written, and the uses of the name being declared resolve to it. The copy keeps the
 * original file for its package, so the rest of the package resolves too.
 */
object GoInlineSuggestions {
    /**
     * The text to show at the caret (what is typed already cut off) and the packages it needs imported; [caretBack]: how far before the
     * end of the text the caret goes once it is accepted (into the quotes of ``regexp.MustCompile(``)``), 0 for the end.
     */
    class Suggestion(val text: String, val imports: Set<String>, val caretBack: Int = 0)

    /** Where the caret goes in the text of a rule ([Suggestion.caretBack]); not shown. */
    const val CARET = "\u0001"

    enum class Kind { DECLARATION, VAR_TYPE, FIELD_TYPE, RETURN, ARGUMENT, LITERAL, RANGE, IF, FOR, SWITCH, LINE }

    /**
     * Where the caret is. [start]: where what is typed in the slot begins, [typed] is that text (the suggestion has to begin with it).
     * [names]: the names declared (`ctx, cancel :=`), the variables of a `range`. [define]: `:=`; [isVar]: `var x =`. [field]: the
     * key of `T{Key: |`. [insert]: what goes into the copy at [start] in place of [typed]; the placeholder is at [start] + [lead].
     */
    class Slot(
        val kind: Kind, val start: Int, val typed: String, val rest: String,
        val names: List<String> = emptyList(), val define: Boolean = false, val isVar: Boolean = false, val field: String? = null,
        val lead: String = "", val closer: String = "",
    ) {
        val insert: String get() = lead + PLACEHOLDER + closer
    }

    const val PLACEHOLDER = "igsSlot__"
    private const val MAX_FILE = 400_000
    private const val MAX_FUNCTION_LINES = 400

    /**
     * The suggestion for [offset] of [text] (the text of the document of [file]); null when no rule is sure. [unit] is one level of
     * indentation. Call under a read action; cancellable.
     */
    fun suggest(file: PsiFile, text: CharSequence, offset: Int, unit: String = "\t"): Suggestion? {
        val go = file as? GoFile ?: return null
        if (text.length > MAX_FILE || DumbService.isDumb(file.project)) return null
        val slot = slotAt(text, offset) ?: return null
        return try {
            val place = GoInlinePlace.of(go, text, offset, slot, unit) ?: return null
            val marked = rules(place) ?: return null
            val full = marked.replace(CARET, "")
            if (!full.startsWith(slot.typed) || full == slot.typed) return null
            // a blank line may have no indent in the text: the caret of such a line is in virtual space
            val lead = if (slot.kind == Kind.LINE && place.indent.isEmpty() && slot.typed.isEmpty()) place.blockIndent else ""
            val caret = marked.indexOf(CARET)
            Suggestion(lead + full.substring(slot.typed.length), place.imports, if (caret < 0) 0 else full.length - caret)
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    private fun rules(place: GoInlinePlace): String? = when (place.slot.kind) {
        Kind.DECLARATION -> GoInlineDeclarations.declaration(place)
        Kind.VAR_TYPE -> GoInlineDeclarations.varType(place)
        Kind.FIELD_TYPE -> GoInlineTypes.byName(place, place.slot.names.single())
        Kind.RETURN -> GoInlineValues.returnValues(place)
        Kind.ARGUMENT -> GoInlineValues.argument(place)
        Kind.LITERAL -> GoInlineValues.literal(place)
        Kind.RANGE -> GoInlineValues.range(place)
        Kind.IF -> GoInlineValues.condition(place)
        Kind.FOR -> GoInlineFlow.loop(place)
        Kind.SWITCH -> GoInlineFlow.switch(place)
        Kind.LINE -> GoInlineBodies.nextLine(place)
    }

    /** Writes [suggestion] at [offset] of [document] and the imports it needs: what accepting it does. */
    fun accept(document: Document, offset: Int, suggestion: Suggestion) {
        document.insertString(offset, suggestion.text)
        addImports(document, suggestion.imports)
    }

    /** The imports of [paths] the file of [document] does not have yet. */
    fun addImports(document: Document, paths: Collection<String>) {
        for (path in paths) GoImports.add(document.immutableCharSequence, path)?.let { document.insertString(it.offset, it.text) }
    }

    // --- the slot, from the text of the line ---

    private val FIELD = Regex("""^\s*([A-Za-z_]\w*)\s+([\w.\[\]*]*)$""")
    private val VAR_NAME = Regex("""^\s*var\s+(\w+)(\s*)$""")
    private val RETURN = Regex("""^return\s+$""")
    private val IF = Regex("""^if\s+$""")
    private val FOR = Regex("""^for\s+(?:(\w+)\s*:=\s*)?$""")
    private val SWITCH = Regex("""^switch\s+(?:(\w+)\s*:=\s*)?$""")
    private val RANGE = Regex("""^for\s+(\w+(?:\s*,\s*\w+)?)\s*:?=\s*range\s+$""")
    private val DECLARATION = Regex("""^(var\s+)?(\w+(?:\s*,\s*\w+)*)\s*(:=|=)\s*$""")
    private val KEY = Regex("""(?:^|[{,])\s*(\w+)\s*:\s*$""")

    private fun isTypedChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.' || c == '!' || c == '&'

    /** The slot at [offset] by the text of its line; null where no rule looks. */
    fun slotAt(text: CharSequence, offset: Int): Slot? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        val before = text.subSequence(lineStart, offset).toString()
        val rest = text.subSequence(offset, lineEnd).toString().trim()
        VAR_NAME.matchEntire(before)?.let { match ->
            if (rest.isNotEmpty()) return null
            return Slot(Kind.VAR_TYPE, offset, "", rest, names = listOf(match.groupValues[1]), lead = if (match.groupValues[2].isEmpty()) " " else "")
        }
        // `Name |` or `Name str|` on a line of a struct: the type of the field (GoLand guesses it from the name too)
        FIELD.matchEntire(before)?.let { match ->
            if ((rest.isEmpty() || rest.startsWith("`")) && match.groupValues[1] !in GoNames.KEYWORDS && GoInlineTypes.insideStruct(text, lineStart)) {
                val typed = match.groupValues[2]
                return Slot(Kind.FIELD_TYPE, offset - typed.length, typed, rest, names = listOf(match.groupValues[1]))
            }
        }
        var typedLength = 0
        while (typedLength < before.length && isTypedChar(before[before.length - 1 - typedLength])) typedLength++
        val typed = before.substring(before.length - typedLength)
        val start = offset - typedLength
        val prefix = before.substring(0, before.length - typedLength)
        val code = prefix.trimStart()
        if (code.isEmpty()) return if (rest.isEmpty() && '.' !in typed && '!' !in typed && '&' !in typed) Slot(Kind.LINE, start, typed, rest) else null
        if (RETURN.matches(code)) return if (rest.isEmpty()) Slot(Kind.RETURN, start, typed, rest) else null
        if (IF.matches(code)) return if (rest.isEmpty() || rest == "{") Slot(Kind.IF, start, typed, rest, closer = if (rest.isEmpty()) " {\n}" else "") else null
        FOR.matchEntire(code)?.let { match ->
            if (rest.isNotEmpty() && rest != "{" || '.' in typed || '!' in typed || '&' in typed) return null
            val name = match.groupValues[1]
            val brace = if (rest.isEmpty()) " {\n}" else ""
            return if (name.isEmpty()) Slot(Kind.FOR, start, typed, rest, closer = brace)
            else Slot(Kind.FOR, start, typed, rest, names = listOf(name), define = true, closer = "; ;$brace")
        }
        SWITCH.matchEntire(code)?.let { match ->
            if (rest.isNotEmpty() && rest != "{" || '.' in typed || '!' in typed || '&' in typed) return null
            val name = match.groupValues[1]
            val brace = if (rest.isEmpty()) " {\n}" else ""
            return if (name.isEmpty()) Slot(Kind.SWITCH, start, typed, rest, closer = brace)
            else Slot(Kind.SWITCH, start, typed, rest, names = listOf(name), define = true, closer = ".(type)$brace")
        }
        RANGE.matchEntire(code)?.let { match ->
            if (rest.isNotEmpty() && rest != "{") return null
            val names = match.groupValues[1].split(',').map { it.trim() }
            return Slot(Kind.RANGE, start, typed, rest, names = names, closer = if (rest.isEmpty()) " {\n}" else "")
        }
        DECLARATION.matchEntire(code)?.let { match ->
            if (rest.isNotEmpty()) return null
            val names = match.groupValues[2].split(',').map { it.trim() }
            if (names.any { it in GoNames.KEYWORDS }) return null
            return Slot(Kind.DECLARATION, start, typed, rest, names = names, define = match.groupValues[3] == ":=", isVar = match.groupValues[1].isNotEmpty())
        }
        // inside a call or a literal written on this line
        val open = unclosedBracket(prefix) ?: return null
        val inside = prefix.substring(open + 1)
        val closedAfter = rest.startsWith(")") || rest.startsWith("}") || rest.startsWith(",")
        return when (prefix[open]) {
            '(' -> if (inside.isBlank() || inside.trimEnd().endsWith(",")) Slot(Kind.ARGUMENT, start, typed, rest, closer = if (closedAfter) "" else ")") else null
            '{' -> {
                val key = KEY.find(inside)?.groupValues?.get(1)
                when {
                    key != null -> Slot(Kind.LITERAL, start, typed, rest, field = key, closer = if (closedAfter) "" else "}")
                    inside.isBlank() && (rest.isEmpty() || rest == "}") -> Slot(Kind.LITERAL, start, typed, rest, closer = if (closedAfter) "" else "}")
                    else -> null
                }
            }
            else -> null
        }
    }

    /** The offset of the innermost `(` or `{` of [line] that is not closed on it; strings and runes are skipped. */
    private fun unclosedBracket(line: String): Int? {
        val stack = ArrayList<Int>()
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '"', '\'' -> {
                    val quote = line[i]
                    i++
                    while (i < line.length && line[i] != quote) i += if (line[i] == '\\') 2 else 1
                }
                '`' -> {
                    i++
                    while (i < line.length && line[i] != '`') i++
                }
                '(', '{', '[' -> stack += i
                ')', '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                '/' -> if (i + 1 < line.length && line[i + 1] == '/') return null
            }
            i++
        }
        return stack.lastOrNull()?.takeIf { line[it] != '[' }
    }

    // --- shared helpers of the rules ---

    /** `GetUser` → `get user`, `ErrNotFound` → `not found` (after [drop]). */
    fun words(name: String, drop: String = ""): String =
        name.removePrefix(drop).split(HUMPS).filter { it.isNotEmpty() }.joinToString(" ") { if (it.length > 1 && it.all(Char::isUpperCase)) it else it.lowercase() }

    private val HUMPS = Regex("""(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_""")

    /** How well the name of a variable fits [wanted] (a parameter, a field): 3 the same but for case, 2 one ends with the other, 0 none. */
    fun nameScore(name: String, wanted: String): Int {
        val a = name.lowercase()
        val b = wanted.lowercase()
        return when {
            a == b -> 3
            a.length >= 2 && b.length >= 2 && (a.endsWith(b) || b.endsWith(a)) -> 2
            else -> 0
        }
    }

    /** The plural forms a collection of [singular] is named by: `user` → `users`, `entry` → `entries`, `userList`. */
    fun plurals(singular: String): Set<String> = buildSet {
        add(singular + "s")
        add(singular + "es")
        if (singular.endsWith("y")) add(singular.dropLast(1) + "ies")
        add(singular + "List")
        add(singular + "Slice")
    }

    /** Whether [name] reads as a plural: `users`, `keys`; not `status`, `address`, `this`. */
    fun isPlural(name: String): Boolean =
        name.length >= 3 && name.endsWith("s") && !name.endsWith("ss") && !name.endsWith("us") && !name.endsWith("is") && name[name.length - 2].isLetter()
}
