package io.github.golangsupport.settings

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import java.io.File

/** One setting of gopls, as `gopls api-json` describes it. Values are JSON texts: `true`, `"100ms"`, `["-tags=x"]`. */
class GoplsOption(
    val name: String,
    /** `bool`, `enum`, `string`, `time.Duration`, `int64`, `[]string`, `map[string]bool`, `map[enum]bool`, `map[string]string`. */
    val type: String,
    val default: String,
    val doc: String,
    /** `build`, `ui.completion`, ...: the group of the documentation of gopls. */
    val hierarchy: String,
    /** Empty, `advanced`, `experimental` or `debug`. */
    val status: String,
    /** The values of an enum, as JSON texts. */
    val enumValues: List<Pair<String, String>>,
    /** The keys a map may have (analyzers, code lenses, hints), each with its default and its description. */
    val enumKeys: List<Key>,
    val deprecation: String,
) {
    class Key(val name: String, val default: String, val doc: String)

    val isBool: Boolean get() = type == "bool"
    val isEnum: Boolean get() = type == "enum"

    /** What is typed as it is and sent as a JSON string: `100ms`, `FullDocumentation`, a path. */
    val isPlainText: Boolean get() = type == "string" || type == "time.Duration" || isEnum

    /** The choices of a combo box; empty for a free-form value. */
    val choices: List<String> get() = if (isBool) listOf("true", "false") else enumValues.map { GoplsCatalogue.display(it.first, plain = true) }
}

/**
 * The settings gopls has, asked of the gopls that is installed: the page of the IDE shows what this version of the server understands,
 * with its own words about every option, and nothing the plugin would have to keep in sync by hand.
 */
object GoplsCatalogue {
    class Loaded(val version: String, val options: List<GoplsOption>)

    fun parse(apiJson: String): List<GoplsOption> {
        val root = runCatching { JsonParser.parseString(apiJson) as? JsonObject }.getOrNull() ?: return emptyList()
        val user = (root.get("Options") as? JsonObject)?.get("User") as? JsonArray ?: return emptyList()
        return user.mapNotNull { element ->
            val option = element as? JsonObject ?: return@mapNotNull null
            GoplsOption(
                name = option.string("Name") ?: return@mapNotNull null,
                type = option.string("Type").orEmpty(),
                default = option.string("Default").orEmpty(),
                doc = option.string("Doc").orEmpty(),
                hierarchy = option.string("Hierarchy").orEmpty(),
                status = option.string("Status").orEmpty(),
                enumValues = (option.get("EnumValues") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { value -> (value.string("Value") ?: return@let null) to value.string("Doc").orEmpty() } },
                enumKeys = ((option.get("EnumKeys") as? JsonObject)?.get("Keys") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let { key -> GoplsOption.Key(display(key.string("Name") ?: return@let null, plain = true), key.string("Default").orEmpty(), key.string("Doc").orEmpty()) } },
                deprecation = option.string("DeprecationMessage").orEmpty(),
            )
        }.sortedWith(compareBy({ it.hierarchy.ifEmpty { "~" } }, { it.name }))
    }

    /** A JSON text the way it is shown and typed in the table: `"100ms"` as `100ms` for plain texts, anything else as it is. */
    fun display(json: String, plain: Boolean): String {
        if (!plain) return json
        val element = runCatching { JsonParser.parseString(json) }.getOrNull()
        return if (element is JsonPrimitive && element.isString) element.asString else json
    }

    /** The JSON text of what was typed for [option]; null when it is not a value of its type. Blank is "the default", and is not asked about here. */
    fun toJson(option: GoplsOption, typed: String): String? {
        val text = typed.trim()
        return when {
            option.isBool -> text.takeIf { it == "true" || it == "false" }
            option.isPlainText -> JsonPrimitive(text).toString()
            option.type == "int64" -> text.toLongOrNull()?.toString()
            else -> {
                val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
                val fits = if (option.type.startsWith("[]")) element.isJsonArray else if (option.type.startsWith("map[")) element.isJsonObject else true
                element.toString().takeIf { fits }
            }
        }
    }

    /**
     * [base] with the [overrides] (option -> JSON text) on top. A map is merged key by key: `{"test": true}` for `codelenses` adds a
     * lens to the ones the plugin sets, it does not switch them off. A text that is not JSON is skipped: the server must start anyway.
     */
    fun merge(base: Map<String, Any>, overrides: Map<String, String>): Map<String, Any> {
        val result = LinkedHashMap(base)
        for ((name, json) in overrides) {
            val value = runCatching { JsonParser.parseString(json) }.getOrNull()?.let(::plain) ?: continue
            val existing = result[name]
            result[name] = if (existing is Map<*, *> && value is Map<*, *>) existing + value else value
        }
        return result
    }

    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isBoolean) element.asBoolean else if (element.isNumber) element.asNumber else element.asString
        is JsonArray -> element.map(::plain)
        is JsonObject -> element.entrySet().associate { it.key to plain(it.value) }
        else -> null
    }

    @Volatile private var cached: Pair<String, Loaded>? = null

    /** Runs `gopls api-json` and `gopls version`, once per executable. Blocks: not for EDT. Null when gopls is not installed or does not answer. */
    fun load(): Loaded? {
        val gopls = GoTool.GOPLS.find() ?: return null
        val key = gopls.path + ":" + gopls.lastModified()
        cached?.takeIf { it.first == key }?.let { return it.second }
        val options = run(gopls, "api-json")?.let(::parse).orEmpty()
        if (options.isEmpty()) return null
        // `golang.org/x/tools/gopls v0.23.0`
        val version = run(gopls, "version")?.lineSequence()?.firstOrNull()?.substringAfterLast(' ').orEmpty()
        return Loaded(version, options).also { cached = key to it }
    }

    private fun run(gopls: File, argument: String): String? =
        runCatching { GoCli.execute(GoCli.toolCommandLine(gopls.path, null, argument), 20_000) }.getOrNull()?.takeIf { it.exitCode == 0 }?.stdout

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
