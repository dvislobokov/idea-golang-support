package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.inspections.GoStructTagInspection
import io.github.golangsupport.ide.inspections.GoStructTags
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTag
import io.github.golangsupport.lang.psi.GoTokenSets

/**
 * Completion inside the raw-string tag of a struct field (also of anonymous structs): keys (`json`, `yaml`, ...), the name in a
 * key's value in the naming style the other fields of the struct use for that key, and the options after a comma. Only raw strings:
 * an interpreted tag needs escaped quotes. The tag is read with [GoStructTags]; the position checks are textual, over the tag text
 * before the caret, so they also serve the typed handler and the confidence.
 */
object GoStructTagCompletion {
    /** Keys offered at a key position, in this order. */
    val KEYS = listOf("json", "yaml", "xml", "toml", "db", "mapstructure", "bson", "validate", "env", "form", "gorm")

    /** Options after the comma, per key. */
    val OPTIONS: Map<String, List<String>> = mapOf(
        "json" to listOf("omitempty", "omitzero", "string"),
        "yaml" to listOf("omitempty", "flow", "inline"),
        "xml" to listOf("attr", "chardata", "innerxml", "comment", "omitempty", "any"),
        "toml" to listOf("omitempty"),
        "bson" to listOf("omitempty", "inline", "minsize"),
        "mapstructure" to listOf("omitempty", "squash", "remain"),
    )

    /** Keys whose value starts with a name taken from the field name (validate / gorm have other grammars). */
    val NAME_KEYS = setOf("json", "yaml", "xml", "toml", "db", "mapstructure", "bson", "env", "form")

    /** Keys whose value is a list of rules, with the separators between them: go-playground/validator (`validate`, gin's `binding`), gorm. */
    val RULE_KEYS: Map<String, String> = mapOf("validate" to ",|", "binding" to ",|", "gorm" to ";")

    /** Naming styles of a field name in a tag; the order breaks ties of [detectStyle]. */
    enum class Style(val label: String) {
        CAMEL("camelCase"), SNAKE("snake_case"), LOWER("lowercase"), LOWER_FIRST("lowerFirst"), AS_IS("as is"), UPPER_SNAKE("UPPER_SNAKE"), KEBAB("kebab-case");

        fun apply(field: String): String {
            val words = words(field)
            return when (this) {
                SNAKE -> words.joinToString("_") { it.lowercase() }
                UPPER_SNAKE -> words.joinToString("_") { it.uppercase() }
                KEBAB -> words.joinToString("-") { it.lowercase() }
                LOWER -> words.joinToString("") { it.lowercase() }
                AS_IS -> field
                LOWER_FIRST -> words.mapIndexed { i, w -> if (i == 0) w.lowercase() else w }.joinToString("")
                CAMEL -> words.mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { c -> c.uppercase() } }.joinToString("")
            }
        }
    }

    /**
     * The style when no other field of the struct uses the key: json, bson, form, xml camelCase; yaml lowercase (what yaml.v3 does
     * without a tag); toml, db, mapstructure snake_case; env UPPER_SNAKE.
     */
    fun defaultStyle(key: String): Style = when (key) {
        "yaml" -> Style.LOWER
        "toml", "db", "mapstructure" -> Style.SNAKE
        "env" -> Style.UPPER_SNAKE
        else -> Style.CAMEL
    }

    /** `UserID` -> `User`, `ID`; `HTTPServer` -> `HTTP`, `Server`; digits stay with the word before. */
    fun words(name: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for ((i, c) in name.withIndex()) {
            if (c == '_') {
                if (sb.isNotEmpty()) out += sb.toString()
                sb.clear()
                continue
            }
            val boundary = sb.isNotEmpty() && c.isUpperCase() &&
                (sb.last().isLowerCase() || sb.last().isDigit() || (sb.last().isUpperCase() && name.getOrNull(i + 1)?.isLowerCase() == true))
            if (boundary) {
                out += sb.toString()
                sb.clear()
            }
            sb.append(c)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /**
     * The style the [samples] (field name, tag name) use: each sample votes for the styles that reproduce its name, unless every
     * style does. The most votes win; a tie goes to the default of [key], then to the [Style] order.
     */
    fun detectStyle(key: String, samples: List<Pair<String, String>>): Style {
        val votes = HashMap<Style, Int>()
        for ((field, name) in samples) {
            val matching = Style.entries.filter { it.apply(field) == name }
            if (matching.isEmpty() || matching.size == Style.entries.size) continue
            for (s in matching) votes.merge(s, 1, Int::plus)
        }
        val best = votes.values.maxOrNull() ?: return defaultStyle(key)
        val top = votes.filterValues { it == best }.keys
        return if (defaultStyle(key) in top) defaultStyle(key) else Style.entries.first { it in top }
    }

    /** What the caret is in. */
    sealed class Position {
        /** A key being typed; [used] are the keys the tag already has. */
        class Key(val prefix: String, val used: Set<String>) : Position()

        /** The name of the value of [key] (before any comma). */
        class Name(val key: String, val prefix: String) : Position()

        /** An option of [key] after a comma; [used] are the options already in the value. */
        class Option(val key: String, val prefix: String, val used: Set<String>) : Position()
    }

    private val VALUE_OPEN = Regex("^([^\\s:\"]+):\"([^\"\\\\]*)$")
    private val KEY_PREFIX = Regex("^[^\\s:\"]*$")

    /** The position at the end of [before] (the tag text up to the caret); [whole] is the whole tag text. Null outside a completable position. */
    fun position(before: String, whole: String = before): Position? {
        val done = GoStructTags.parse(before)
        val lastEnd = done.pairs.lastOrNull()?.end ?: 0
        val rest = before.substring(lastEnd)
        val tail = rest.trimStart(' ')
        val separated = lastEnd == 0 || rest.length != tail.length
        if (!separated) return null
        val used = (done.pairs.map { it.key } + GoStructTags.parse(whole).pairs.map { it.key }).toSet()
        if (KEY_PREFIX.matches(tail)) return Position.Key(tail, used)
        val m = VALUE_OPEN.matchEntire(tail) ?: return null
        val key = m.groupValues[1]
        val value = m.groupValues[2]
        RULE_KEYS[key]?.let { separators ->
            val last = value.indexOfLast { it in separators }
            val prefix = value.substring(last + 1)
            // `min=3`, `column:name`: an argument is being typed, no rule to offer
            if ('=' in prefix || ':' in prefix) return null
            return Position.Option(key, prefix, value.split(*separators.toCharArray()).map { it.substringBefore('=') }.dropLast(1).toSet())
        }
        val comma = value.lastIndexOf(',')
        if (comma < 0) return if (key in NAME_KEYS) Position.Name(key, value) else null
        if (key !in OPTIONS) return null
        return Position.Option(key, value.substring(comma + 1), value.split(',').drop(1).dropLast(1).toSet())
    }

    /** The tag and the tag text before the caret when [leaf] is in a raw-string tag and the caret [offset] is inside its backquotes; null otherwise. */
    fun tagAt(leaf: PsiElement, offset: Int): Pair<GoTag, String>? {
        if (!GoTokenSets.STRING_LITERALS.contains(leaf.node.elementType)) return null
        val literal = leaf.parent as? GoStringLiteral ?: return null
        val tag = literal.parent as? GoTag ?: return null
        if (literal.rawString == null) return null
        val text = leaf.text
        val inLeaf = offset - leaf.textRange.startOffset
        val limit = if (text.length >= 2 && text.endsWith("`")) text.length - 1 else text.length
        if (inLeaf < 1 || inLeaf > limit) return null
        return tag to text.substring(1, inLeaf).replace("\r", "")
    }

    /** The position at [offset] of [leaf], or null. */
    fun positionAt(leaf: PsiElement, offset: Int): Position? {
        val (tag, before) = tagAt(leaf, offset) ?: return null
        val whole = GoStructTagInspection.valueOf(tag.stringLiteral) ?: before
        return position(before, whole)
    }

    /**
     * The names the field declaration of [tag] offers for [key], in the style the other fields of the struct use for it; with
     * [observedOnly] nothing when no other field has the key (instead of the names in [defaultStyle]).
     */
    fun namesFor(tag: GoTag, key: String, observedOnly: Boolean = false): List<String> {
        val declaration = tag.parent as? GoFieldDeclaration ?: return emptyList()
        val struct = declaration.parent as? GoStructType ?: return emptyList()
        val samples = samples(struct, key, declaration)
        if (observedOnly && samples.isEmpty()) return emptyList()
        val style = detectStyle(key, samples)
        return fieldNames(declaration).map { style.apply(it) }.distinct()
    }

    /** GoLand's four names of a field in a tag value, in its order: `full-name`, `full_name`, `FullName`, `fullName`. */
    fun nameStyles(field: String): List<String> = listOf(Style.KEBAB, Style.SNAKE, Style.AS_IS, Style.LOWER_FIRST).map { it.apply(field) }.distinct()

    /** The style the fields of [struct] use for [key] ([defaultStyle] when none has it): what Add Tag Key to All Fields writes. */
    fun styleFor(struct: GoStructType, key: String): Style = detectStyle(key, samples(struct, key, null))

    /** (field name, name in the tag) of the fields of [struct] but [except] that have [key]. */
    private fun samples(struct: GoStructType, key: String, except: GoFieldDeclaration?): List<Pair<String, String>> {
        val samples = ArrayList<Pair<String, String>>()
        for (other in struct.fieldDeclarationList) {
            if (other === except) continue
            val value = other.tag?.let { GoStructTagInspection.valueOf(it.stringLiteral) }?.let { GoStructTags.parse(it).lookup(key) } ?: continue
            val name = value.substringBefore(',')
            if (name.isEmpty() || name == "-") continue
            fieldNames(other).forEach { samples += it to name }
        }
        return samples
    }

    private fun fieldNames(d: GoFieldDeclaration): List<String> =
        d.anonymousFieldDefinition?.let { listOfNotNull(it.name) } ?: d.fieldDefinitionList.mapNotNull { it.name }
}

/**
 * Struct tags: `:` typed after a known key writes `""` and opens the name list; `,` typed inside a value opens the options (`|` / `;`
 * the next rule of validate / gorm). Raw
 * string tags only; the provider decides what the popup shows.
 */
class GoStructTagTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        // `,` opens the options of a key; `|` and `;` the next rule of validate / gorm
        if (charTyped !in ",|;" || file !is GoFile) return Result.CONTINUE
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, project)) return Result.CONTINUE
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset - 1) ?: return Result.CONTINUE
        val (_, before) = GoStructTagCompletion.tagAt(leaf, offset) ?: return Result.CONTINUE
        if (GoStructTagCompletion.position("$before$charTyped") !is GoStructTagCompletion.Position.Option) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != ':' || file !is GoFile) return Result.CONTINUE
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, project)) return Result.CONTINUE
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset - 1) ?: return Result.CONTINUE
        val (_, typed) = GoStructTagCompletion.tagAt(leaf, offset) ?: return Result.CONTINUE
        if (!typed.endsWith(":")) return Result.CONTINUE
        val before = typed.dropLast(1)
        // `key` at a key position, a known key
        val position = GoStructTagCompletion.position(before) as? GoStructTagCompletion.Position.Key ?: return Result.CONTINUE
        if (position.prefix !in GoStructTagCompletion.KEYS) return Result.CONTINUE
        val doc = editor.document
        if (doc.charsSequence.getOrNull(offset) != '"') {
            doc.insertString(offset, "\"\"")
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
        editor.caretModel.moveToOffset(offset + 1)
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}
