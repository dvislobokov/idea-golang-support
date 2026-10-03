package io.github.golangsupport.ci

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** An inspection as a SARIF rule: [description] is plain text (the HTML of the inspection description with the tags taken out). */
data class GoSarifRule(val id: String, val name: String, val description: String, val group: String, val level: GoSarifLevel)

/** 1-based lines and columns, [endColumn] exclusive, columns in UTF-16 code units (what Java offsets are; the run says so in `columnKind`). */
data class GoSarifRegion(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)

data class GoSarifResult(val ruleId: String, val level: GoSarifLevel, val message: String, val uri: String, val region: GoSarifRegion)

/**
 * SARIF 2.1.0 of one `go-inspect` run, written without the platform so that the tests compare it with a golden file. Results come out
 * sorted by file, position and rule: the same project gives the same bytes, and CI can diff two reports.
 */
object GoSarif {
    const val SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
    const val TOOL_NAME = "Go Project Support"
    const val SRCROOT = "%SRCROOT%"

    /**
     * [srcRootUri] (`file:///…/`) goes to `originalUriBaseIds` when given: viewers resolve the relative URIs with it, GitHub code scanning
     * ignores it. [notifications] are inspections that failed on a file: reported, not hidden, but they do not fail the run.
     */
    fun write(toolVersion: String?, rules: List<GoSarifRule>, results: List<GoSarifResult>, srcRootUri: String? = null, notifications: List<String> = emptyList()): String {
        val sortedRules = rules.sortedBy { it.id }
        val ruleIndex = sortedRules.withIndex().associate { (i, r) -> r.id to i }
        val driver = JsonObject().apply {
            addProperty("name", TOOL_NAME)
            toolVersion?.let { addProperty("version", it); addProperty("semanticVersion", it) }
            add("rules", JsonArray().apply { sortedRules.forEach { add(rule(it)) } })
        }
        val run = JsonObject().apply {
            add("tool", JsonObject().apply { add("driver", driver) })
            if (srcRootUri != null) add("originalUriBaseIds", JsonObject().apply { add(SRCROOT, JsonObject().apply { addProperty("uri", srcRootUri) }) })
            add("invocations", JsonArray().apply { add(invocation(notifications)) })
            addProperty("columnKind", "utf16CodeUnits")
            val ordered = results.sortedWith(compareBy({ it.uri }, { it.region.startLine }, { it.region.startColumn }, { it.ruleId }, { it.message }))
            add("results", JsonArray().apply { ordered.forEach { add(result(it, ruleIndex[it.ruleId])) } })
        }
        val root = JsonObject().apply {
            addProperty("\$schema", SCHEMA)
            addProperty("version", "2.1.0")
            add("runs", JsonArray().apply { add(run) })
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
    }

    /** [start]..[end] offsets of [text] as a region; an empty range still covers one column, as the editor shows it. */
    fun region(text: CharSequence, start: Int, end: Int): GoSarifRegion {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val (startLine, startColumn) = position(text, s)
        val (endLine, endColumn) = position(text, e)
        return GoSarifRegion(startLine, startColumn, endLine, if (endLine == startLine && endColumn <= startColumn) startColumn + 1 else endColumn)
    }

    /** HTML of an inspection description to one paragraph of text: the rule's `fullDescription`. */
    fun plainText(html: String?): String = html.orEmpty()
        .replace(Regex("(?is)<!--.*?-->"), "")
        .replace(Regex("(?i)<br\\s*/?>|</?(p|li|ul|ol|pre|div|h[1-6])\\b[^>]*>"), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    private fun position(text: CharSequence, offset: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        for (i in 0 until offset) if (text[i] == '\n') { line++; lineStart = i + 1 }
        return line to offset - lineStart + 1
    }

    private fun rule(rule: GoSarifRule) = JsonObject().apply {
        addProperty("id", rule.id)
        addProperty("name", rule.id)
        add("shortDescription", text(rule.name))
        add("fullDescription", text(rule.description.ifEmpty { rule.name }))
        add("defaultConfiguration", JsonObject().apply { addProperty("level", rule.level.sarif) })
        add("properties", JsonObject().apply { add("tags", JsonArray().apply { add(rule.group) }) })
    }

    private fun invocation(notifications: List<String>) = JsonObject().apply {
        addProperty("executionSuccessful", true)
        if (notifications.isNotEmpty()) add("toolExecutionNotifications", JsonArray().apply {
            notifications.sorted().forEach { add(JsonObject().apply { addProperty("level", "warning"); add("message", text(it)) }) }
        })
    }

    private fun result(result: GoSarifResult, index: Int?) = JsonObject().apply {
        addProperty("ruleId", result.ruleId)
        index?.let { addProperty("ruleIndex", it) }
        addProperty("level", result.level.sarif)
        add("message", text(result.message))
        add("locations", JsonArray().apply {
            add(JsonObject().apply {
                add("physicalLocation", JsonObject().apply {
                    add("artifactLocation", JsonObject().apply { addProperty("uri", result.uri); addProperty("uriBaseId", SRCROOT) })
                    add("region", JsonObject().apply {
                        addProperty("startLine", result.region.startLine)
                        addProperty("startColumn", result.region.startColumn)
                        addProperty("endLine", result.region.endLine)
                        addProperty("endColumn", result.region.endColumn)
                    })
                })
            })
        })
    }

    private fun text(value: String) = JsonObject().apply { addProperty("text", value) }
}
