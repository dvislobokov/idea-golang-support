package io.github.golangsupport.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.execution.ParametersListUtil

/**
 * What the plugin sets for gopls by itself, before anything of the gopls page. Here and not with the language server: the page shows
 * these values as the ones in effect, and it has to work where the LSP module of the platform is not loaded.
 */
object GoplsDefaults {
    fun of(settings: GoSettings): Map<String, Any> = buildMap {
        put("staticcheck", settings.goplsStaticcheck)
        put("gofumpt", settings.goplsGofumpt)
        settings.buildTagArguments().takeIf { it.isNotEmpty() }?.let { put("buildFlags", it) }
        // The lenses of go.mod (check for upgrades, upgrade, tidy, vendor) and of `//go:generate`; vulncheck is off in gopls by default.
        // Not `test`: tests are run from the gutter, with the test tree, and a second way next to it would only run them worse.
        // Only `vulncheck`: with the older `run_govulncheck` next to it gopls complains in a balloon that never goes away (seen live).
        put("codelenses", mapOf("generate" to true, "regenerate_cgo" to true, "tidy" to true, "upgrade_dependency" to true, "vendor" to true, "vulncheck" to true))
        // No `usePlaceholders`: with it a completed call comes as `os.Open(name string)`, the signature as text to type over (seen live).
        put("semanticTokens", true)
        // Not the packages: gopls marks the last part of an import path as one, and a path in two colours reads worse than a string
        // (asked by the user); a token has no place for the plugin to tell an import from a call. The names of packages in the code
        // are coloured by the annotator of the plugin.
        put("semanticTokenTypes", mapOf("namespace" to false))
        if (settings.goplsInlayHints) put("hints", HINTS.associateWith { true })
    }

    private val HINTS = listOf("parameterNames", "assignVariableTypes", "rangeVariableTypes", "constantValues", "compositeLiteralFields", "functionTypeParameters")
}

/** The values of the gopls page between the forms they have: JSON texts in the settings, what a person types and ticks in the controls. */
object GoplsValues {
    /** `-tags=integration -mod=mod`, one item per word (quotes keep spaces together) -> `["-tags=integration","-mod=mod"]`. */
    fun listToJson(typed: String): String = JsonArray().apply { ParametersListUtil.parse(typed.replace('\n', ' ')).forEach { add(it) } }.toString()

    fun jsonToList(json: String): String {
        val array = runCatching { JsonParser.parseString(json) as? JsonArray }.getOrNull() ?: return ""
        return ParametersListUtil.join(array.mapNotNull { (it as? JsonPrimitive)?.asString })
    }

    /** `GOFLAGS=-mod=mod GOOS=linux` -> `{"GOFLAGS":"-mod=mod","GOOS":"linux"}`; null when a word has no `=`. */
    fun envToJson(typed: String): String? {
        val result = JsonObject()
        for (word in ParametersListUtil.parse(typed.replace('\n', ' '))) {
            val equals = word.indexOf('=')
            if (equals <= 0) return null
            result.addProperty(word.substring(0, equals), word.substring(equals + 1))
        }
        return result.toString()
    }

    fun jsonToEnv(json: String): String {
        val map = runCatching { JsonParser.parseString(json) as? JsonObject }.getOrNull() ?: return ""
        return ParametersListUtil.join(map.entrySet().map { it.key + "=" + ((it.value as? JsonPrimitive)?.asString ?: it.value.toString()) })
    }

    /** `{"tidy":false}` -> the keys and their values; anything that is not a boolean is not a tick. */
    fun jsonToFlags(json: String?): Map<String, Boolean> {
        val map = json?.let { runCatching { JsonParser.parseString(it) as? JsonObject }.getOrNull() } ?: return emptyMap()
        return map.entrySet().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isBoolean }?.let { key to it.asBoolean } }.toMap()
    }

    /** The override of a map of flags: only the ticks that differ from what is in effect without it; null when none does. */
    fun flagsToJson(ticked: Map<String, Boolean>, inEffect: Map<String, Boolean>): String? {
        val changed = ticked.filter { (key, value) -> inEffect[key] != value }
        return if (changed.isEmpty()) null else JsonObject().apply { changed.toSortedMap().forEach { (key, value) -> addProperty(key, value) } }.toString()
    }
}

/** The documentation of gopls is Markdown written for a web page; here it is a line under a control and a tooltip. */
object GoplsDocs {
    private val LINK = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    private val CODE = Regex("""`([^`]+)`""")
    private val FENCED = Regex("""```[a-z0-9]*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)

    /** The first sentence, without the name of the option it starts with: `staticcheck configures the default set...` -> `Configures the default set...`. */
    fun summary(name: String, doc: String): String {
        val firstParagraph = doc.trim().substringBefore("\n\n").replace('\n', ' ')
        val sentence = Regex("""^(.*?[.!?])(\s|$)""").find(firstParagraph)?.groupValues?.get(1) ?: firstParagraph
        // the keys of a map are described as `"generate": Run go generate` and `"escape" controls diagnostics...`
        val words = sentence.removePrefix("\"$name\"").removePrefix(name).trimStart(':', ' ').trim()
        return html(words.replaceFirstChar { it.uppercaseChar() })
    }

    /** Enough of Markdown for a tooltip: code, links (relative ones lead to the documentation of gopls), paragraphs. */
    fun html(markdown: String): String {
        var text = StringUtil.escapeXmlEntities(markdown.trim())
        text = FENCED.replace(text) { "<pre>" + it.groupValues[1].trimEnd() + "</pre>" }
        text = CODE.replace(text) { "<code>" + it.groupValues[1] + "</code>" }
        text = LINK.replace(text) { match ->
            val target = match.groupValues[2]
            val url = if (target.startsWith("http")) target else "https://go.dev/gopls/" + target.removeSuffix(".md").replace(".md#", "#")
            "<a href=\"$url\">${match.groupValues[1]}</a>"
        }
        return text.replace("\n\n", "<br><br>")
    }

    /** The titles of the groups: the hierarchy of the documentation of gopls, in the words of a settings page. */
    fun groupTitle(hierarchy: String): String = when (hierarchy) {
        "build" -> "Build"
        "formatting" -> "Formatting"
        "ui" -> "General"
        "ui.completion" -> "Completion"
        "ui.diagnostic" -> "Diagnostics"
        "ui.documentation" -> "Documentation"
        "ui.inlayhint" -> "Inlay Hints"
        "ui.navigation" -> "Navigation"
        "" -> "Other"
        else -> hierarchy.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() }
    }

    /** `completionBudget` -> `Completion budget`: a label reads better than an identifier, the identifier stays in the tooltip. */
    fun title(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercaseChar() }
}
