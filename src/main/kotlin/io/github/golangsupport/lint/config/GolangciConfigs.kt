package io.github.golangsupport.lint.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

/** Finding and reading golangci-lint configuration files; pure, never throws (errors come back as [GolangciConfigResult.Failed]). */
object GolangciConfigs {
    /** The names golangci looks for, in its order. */
    val NAMES: List<String> = listOf(".golangci.yml", ".golangci.yaml", ".golangci.toml", ".golangci.json")

    enum class Format { YAML, JSON, TOML }

    /**
     * The configuration golangci would use for a run in [dir]: the first of [NAMES] in [dir], then in each parent, up to [stopAt] inclusive
     * (the module root or project root; null walks to the file-system root like golangci does before it falls back to the home directory).
     */
    fun find(dir: File, stopAt: File? = null): File? {
        val stop = stopAt?.absoluteFile?.normalize()
        var current: File? = dir.absoluteFile.normalize()
        while (current != null) {
            NAMES.map { File(current, it) }.firstOrNull { it.isFile }?.let { return it }
            if (stop != null && current == stop) return null
            current = current.parentFile
        }
        return null
    }

    fun formatOf(fileName: String): Format? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "yml", "yaml" -> Format.YAML
        "json" -> Format.JSON
        "toml" -> Format.TOML
        else -> null
    }

    fun load(file: File): GolangciConfigResult {
        val format = formatOf(file.name) ?: return GolangciConfigResult.Unsupported("unknown configuration format: ${file.name}", file.path)
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return GolangciConfigResult.Failed("cannot read ${file.name}: ${e.message ?: e.javaClass.simpleName}", file.path)
        }
        return when (val result = parse(text, format)) {
            is GolangciConfigResult.Parsed -> result.copy(file = file.path)
            is GolangciConfigResult.Unsupported -> result.copy(file = file.path)
            is GolangciConfigResult.Failed -> result.copy(file = file.path)
        }
    }

    fun parse(text: String, format: Format): GolangciConfigResult {
        val tree: Any? = try {
            when (format) {
                Format.YAML -> Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
                Format.JSON -> fromJson(JsonParser.parseString(text))
                Format.TOML -> return GolangciConfigResult.Unsupported("TOML configurations are not supported: the IDE bundles no TOML parser; use .golangci.yml")
            }
        } catch (e: Exception) {
            return GolangciConfigResult.Failed(e.message?.lineSequence()?.take(4)?.joinToString(" ")?.trim() ?: e.javaClass.simpleName)
        }
        if (tree == null) return GolangciConfigResult.Parsed(GolangciConfig(version = 1))
        val root = normalize(tree) as? Map<*, *> ?: return GolangciConfigResult.Failed("the top level of the configuration is not a mapping")
        @Suppress("UNCHECKED_CAST")
        return try {
            GolangciConfigResult.Parsed(GolangciConfigReader(root as Map<String, Any?>).read())
        } catch (e: Exception) {
            GolangciConfigResult.Failed("invalid configuration: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Keys to strings (YAML allows `true:` or `1:`), nested all the way down. */
    private fun normalize(node: Any?): Any? = when (node) {
        is Map<*, *> -> node.entries.associate { (k, v) -> k.toString() to normalize(v) }
        is List<*> -> node.map(::normalize)
        else -> node
    }

    private fun fromJson(element: JsonElement?): Any? = when (element) {
        null -> null
        is JsonObject -> element.entrySet().associate { (k, v) -> k to fromJson(v) }
        is JsonArray -> element.map(::fromJson)
        is JsonPrimitive -> when {
            element.isBoolean -> element.asBoolean
            element.isNumber -> element.asNumber.let { n -> n.toString().toLongOrNull() ?: n.toDouble() }
            else -> element.asString
        }
        else -> null
    }

    /** Whether golangci hides the issue: test files with `run.tests: false`, excluded dirs and paths, then the exclusion rules. */
    fun isExcluded(config: GolangciConfig, relPath: String, linter: String, text: String, sourceLine: String? = null): Boolean =
        GolangciExclusions.isExcluded(config, relPath, linter, text, sourceLine)
}

/** Reads the parsed tree of either version into [GolangciConfig]. */
private class GolangciConfigReader(private val root: Map<String, Any?>) {
    private val warnings = mutableListOf<String>()
    private val version = when (val v = root["version"]) {
        null -> 1
        is Number -> v.toInt()
        else -> v.toString().trim().toIntOrNull() ?: 1.also { warnings += "unknown version '$v', read as 1" }
    }

    private fun section(parent: Map<String, Any?>?, key: String): Map<String, Any?> = GolangciSettings.map(parent?.get(key)).orEmpty()

    fun read(): GolangciConfig = if (version >= 2) readV2() else readV1()

    private fun readV2(): GolangciConfig {
        val linters = section(root, "linters")
        val exclusions = section(linters, "exclusions")
        val formatters = section(root, "formatters")
        val run = section(root, "run")
        val default = when (val d = linters["default"]?.toString()?.lowercase()) {
            null, "standard" -> GolangciDefault.STANDARD
            "all" -> GolangciDefault.ALL
            "none" -> GolangciDefault.NONE
            "fast" -> GolangciDefault.FAST
            else -> GolangciDefault.STANDARD.also { warnings += "unknown linters.default '$d'" }
        }
        val presets = GolangciSettings.strings(exclusions["presets"])
        val rules = GolangciSettings.list(exclusions["rules"]).mapNotNull { rule(it, "linters.exclusions.rules", caseSensitive = false) } +
            presets.flatMap { name -> GolangciExclusions.preset(name, 2) ?: emptyList<GolangciExclusionRule>().also { warnings += "unknown exclusion preset '$name'" } }
        val severity = section(root, "severity")
        return GolangciConfig(
            version = 2, default = default, enable = GolangciSettings.strings(linters["enable"]), disable = GolangciSettings.strings(linters["disable"]),
            settings = settingsMap(section(linters, "settings")), formatters = GolangciSettings.strings(formatters["enable"]),
            formatterSettings = settingsMap(section(formatters, "settings")), exclusionRules = rules, exclusionPresets = presets,
            excludedPaths = regexes(exclusions["paths"], "linters.exclusions.paths", false), keptPaths = regexes(exclusions["paths-except"], "linters.exclusions.paths-except", false),
            generated = generated(exclusions["generated"]), buildTags = GolangciSettings.strings(run["build-tags"]), tests = GolangciSettings.bool(run["tests"]) ?: true,
            defaultSeverity = severity["default"]?.toString(), severityRules = severityRules(severity, false), warnings = warnings,
        )
    }

    private fun readV1(): GolangciConfig {
        val linters = section(root, "linters")
        val issues = section(root, "issues")
        val run = section(root, "run")
        val caseSensitive = GolangciSettings.bool(issues["exclude-case-sensitive"]) ?: false
        val enableAll = GolangciSettings.bool(linters["enable-all"]) == true
        val disableAll = GolangciSettings.bool(linters["disable-all"]) == true
        if (enableAll && disableAll) warnings += "linters.enable-all and linters.disable-all are both set"
        val default = when {
            enableAll -> GolangciDefault.ALL
            disableAll -> GolangciDefault.NONE
            else -> GolangciDefault.STANDARD
        }
        val rules = mutableListOf<GolangciExclusionRule>()
        GolangciSettings.list(issues["exclude-rules"]).mapNotNullTo(rules) { rule(it, "issues.exclude-rules", caseSensitive) }
        GolangciSettings.strings(issues["exclude"]).forEach { pattern ->
            compile(pattern, "issues.exclude", !caseSensitive)?.let { rules += GolangciExclusionRule(text = it) }
        }
        val useDefault = GolangciSettings.bool(issues["exclude-use-default"]) ?: true
        val include = GolangciSettings.strings(issues["include"]).toSet()
        if (useDefault) rules += GolangciExclusions.V1_DEFAULTS.filter { it.preset !in include }
        val dirs = GolangciSettings.strings(issues["exclude-dirs"]) + GolangciSettings.strings(run["skip-dirs"])
        val dirsDefault = GolangciSettings.bool(issues["exclude-dirs-use-default"]) ?: GolangciSettings.bool(run["skip-dirs-use-default"]) ?: true
        val files = GolangciSettings.strings(issues["exclude-files"]) + GolangciSettings.strings(run["skip-files"])
        val generated = when {
            issues.containsKey("exclude-generated") -> generated(issues["exclude-generated"])
            GolangciSettings.bool(issues["exclude-generated-strict"]) == true -> GolangciGenerated.STRICT
            else -> GolangciGenerated.LAX
        }
        val severity = section(root, "severity")
        return GolangciConfig(
            version = 1, default = default, fastOnly = GolangciSettings.bool(linters["fast"]) == true, presets = GolangciSettings.strings(linters["presets"]),
            enable = GolangciSettings.strings(linters["enable"]), disable = GolangciSettings.strings(linters["disable"]),
            settings = settingsMap(section(root, "linters-settings")), exclusionRules = rules,
            exclusionPresets = if (useDefault) GolangciExclusions.PRESETS.filter { p -> GolangciExclusions.presetIds(p).any { it !in include } } else emptyList(),
            excludedPaths = files.mapNotNull { compile(it, "issues.exclude-files", false) },
            excludedDirs = (if (dirsDefault) GolangciExclusions.V1_DEFAULT_DIRS else emptyList()).map(::Regex) + dirs.mapNotNull { compile(it, "issues.exclude-dirs", false) },
            generated = generated, buildTags = GolangciSettings.strings(run["build-tags"]), tests = GolangciSettings.bool(run["tests"]) ?: true,
            caseSensitive = caseSensitive, defaultSeverity = (severity["default-severity"] ?: severity["default"])?.toString(),
            severityRules = severityRules(severity, caseSensitive), warnings = warnings,
        )
    }

    private fun settingsMap(section: Map<String, Any?>): Map<String, Map<String, Any?>> =
        section.entries.mapNotNull { (k, v) -> GolangciSettings.map(v)?.let { k.lowercase() to it } }.toMap()

    private fun generated(value: Any?): GolangciGenerated = when (val v = value?.toString()?.lowercase()) {
        null, "lax" -> GolangciGenerated.LAX
        "strict" -> GolangciGenerated.STRICT
        "disable" -> GolangciGenerated.DISABLE
        else -> GolangciGenerated.LAX.also { warnings += "unknown generated mode '$v'" }
    }

    /** golangci requires a rule to have at least one condition; a rule without any would hide everything, so it is dropped. */
    private fun rule(item: Any?, where: String, caseSensitive: Boolean): GolangciExclusionRule? {
        val m = GolangciSettings.map(item) ?: return null.also { warnings += "$where: not a mapping" }
        val before = warnings.size
        val rule = GolangciExclusionRule(
            path = m["path"]?.toString()?.let { compile(it, "$where.path", false) },
            pathExcept = m["path-except"]?.toString()?.let { compile(it, "$where.path-except", false) },
            text = m["text"]?.toString()?.let { compile(it, "$where.text", !caseSensitive) },
            source = m["source"]?.toString()?.let { compile(it, "$where.source", !caseSensitive) },
            linters = GolangciSettings.strings(m["linters"]).map(GolangciLinters::canonical).toSet(),
        )
        // a pattern that did not compile would widen the rule to everything its other conditions match
        if (warnings.size > before) return null
        if (rule.path == null && rule.pathExcept == null && rule.text == null && rule.source == null && rule.linters.isEmpty()) {
            warnings += "$where: a rule without conditions is ignored"
            return null
        }
        return rule
    }

    private fun severityRules(severity: Map<String, Any?>, caseSensitive: Boolean): List<GolangciSeverityRule> =
        GolangciSettings.list(severity["rules"]).mapNotNull { item ->
            val level = GolangciSettings.map(item)?.get("severity")?.toString() ?: return@mapNotNull null
            rule(item, "severity.rules", caseSensitive)?.let { GolangciSeverityRule(level, it) }
        }

    private fun regexes(value: Any?, where: String, ignoreCase: Boolean): List<Regex> =
        GolangciSettings.strings(value).mapNotNull { compile(it, where, ignoreCase) }

    private fun compile(pattern: String, where: String, ignoreCase: Boolean): Regex? = try {
        GolangciExclusions.regex(pattern, ignoreCase)
    } catch (e: Exception) {
        warnings += "$where: invalid regular expression '$pattern'"
        null
    }
}

/** golangci's exclusion processors: path filters and rules, plus the built-in rule sets of both versions. */
object GolangciExclusions {
    /** RE2 POSIX classes in Java spelling; first: the built-in rules below are compiled with [regex] at initialization. */
    private val POSIX = mapOf(
        "alpha" to "\\p{Alpha}", "digit" to "\\p{Digit}", "alnum" to "\\p{Alnum}", "upper" to "\\p{Upper}", "lower" to "\\p{Lower}", "space" to "\\s",
        "punct" to "\\p{Punct}", "xdigit" to "\\p{XDigit}", "word" to "\\w",
    )
    /** v1 `exclude-dirs-use-default` patterns (matched against the directory of the file). */
    val V1_DEFAULT_DIRS: List<String> = listOf("(^|/)vendor($|/)", "(^|/)third_party($|/)", "(^|/)testdata($|/)", "(^|/)examples($|/)", "(^|/)Godeps($|/)", "(^|/)builtin($|/)")

    /** v2 exclusion presets, in golangci's order. */
    val PRESETS: List<String> = listOf("comments", "std-error-handling", "common-false-positives", "legacy")

    private class Exc(val id: String, val preset: String, val linters: Set<String>, val text: String)

    /** The `EXC` default patterns of v1 and the v2 preset each one went to (golangci `config.DefaultExcludePatterns`, `LinterExclusionPresets`). */
    private val EXC: List<Exc> = listOf(
        Exc("EXC0001", "std-error-handling", setOf("errcheck"),
            "Error return value of .((os\\.)?std(out|err)\\..*|.*Close|.*Flush|os\\.Remove(All)?|.*print(f|ln)?|os\\.(Un)?Setenv). is not checked"),
        Exc("EXC0002", "legacy", setOf("revive"), "(comment on exported (method|function|type|const)|should have( a package)? comment|comment should be of the form)"),
        Exc("EXC0003", "legacy", setOf("revive"), "func name will be used as test\\.Test.* by other packages, and that stutters; consider calling this"),
        Exc("EXC0004", "legacy", setOf("govet"), "(possible misuse of unsafe.Pointer|should have signature)"),
        Exc("EXC0005", "legacy", setOf("staticcheck"), "SA4011"),
        Exc("EXC0006", "common-false-positives", setOf("gosec"), "G103: Use of unsafe calls should be audited"),
        Exc("EXC0007", "common-false-positives", setOf("gosec"), "G204: Subprocess launched with variable"),
        Exc("EXC0008", "common-false-positives", setOf("gosec"), "G104"),
        Exc("EXC0009", "common-false-positives", setOf("gosec"), "(G301|G302|G307): Expect (directory permissions to be 0750|file permissions to be 0600) or less"),
        Exc("EXC0010", "common-false-positives", setOf("gosec"), "G304: Potential file inclusion via variable"),
        Exc("EXC0011", "comments", setOf("stylecheck", "staticcheck"), "(ST1000|ST1020|ST1021|ST1022)"),
        Exc("EXC0012", "comments", setOf("revive"), "exported (.+) should have comment( \\(or a comment on this block\\))? or be unexported"),
        Exc("EXC0013", "comments", setOf("revive"), "package comment should be of the form \"(.+)..."),
        Exc("EXC0014", "comments", setOf("revive"), "comment on exported (.+) should be of the form \"(.+)...\""),
        Exc("EXC0015", "comments", setOf("revive"), "should have a package comment"),
    )

    /** v1 rules applied while `issues.exclude-use-default` is on (the default), minus `issues.include` ids. */
    val V1_DEFAULTS: List<GolangciExclusionRule> = EXC.map { GolangciExclusionRule(text = regex(it.text, true), linters = it.linters, preset = it.id) }

    fun presetIds(preset: String): Set<String> = EXC.filter { it.preset == preset }.map { it.id }.toSet()

    /** The rules of v2 exclusion [preset] (null: unknown preset). */
    fun preset(name: String, version: Int): List<GolangciExclusionRule>? =
        EXC.filter { it.preset == name }.takeIf { it.isNotEmpty() }?.map { exc ->
            GolangciExclusionRule(text = regex(exc.text, true), linters = exc.linters.map { GolangciLinters.reportedAs(it, version) }.toSet(), preset = name)
        }

    /**
     * A Go RE2 pattern as golangci compiles it (`(?i)` prefixed for text and source unless case-sensitive). The RE2 subset golangci configs use
     * is Java syntax as well; the two spellings that differ are converted: `(?P<name>` and POSIX classes `[[:alpha:]]`.
     */
    fun regex(pattern: String, ignoreCase: Boolean): Regex {
        var p = pattern.replace("(?P<", "(?<")
        POSIX.forEach { (posix, java) -> p = p.replace("[:$posix:]", java) }
        return if (ignoreCase) Regex(p, RegexOption.IGNORE_CASE) else Regex(p)
    }


    /** `go list ./...` never yields these: `testdata`, `vendor`, and elements starting with `.` or `_` (golangci does not see them at all). */
    fun skippedByGoTool(relPath: String): Boolean =
        relPath.split('/').dropLast(1).any { it == "testdata" || it == "vendor" || it.startsWith(".") || it.startsWith("_") }

    /**
     * Whether golangci would hide an issue of [linter] (golangci naming: `govet`, `staticcheck`; see [GolangciLinters.reportedAs]) with message [text] at
     * [relPath] (relative to the configuration's directory, any separator) on a line reading [sourceLine]. Generated files are the engine's call:
     * see [GolangciConfig.generated].
     */
    fun isExcluded(config: GolangciConfig, relPath: String, linter: String, text: String, sourceLine: String? = null): Boolean {
        val path = relPath.replace('\\', '/').removePrefix("./")
        val name = GolangciLinters.reportedAs(linter, config.version)
        if (!config.tests && path.endsWith("_test.go")) return true
        if (skippedByGoTool(path)) return true
        val dir = path.substringBeforeLast('/', ".")
        if (config.excludedDirs.any { it.containsMatchIn(dir) }) return true
        if (config.excludedPaths.any { it.containsMatchIn(path) } && config.keptPaths.none { it.containsMatchIn(path) }) return true
        return config.exclusionRules.any { it.matches(path, name, text, sourceLine) }
    }

    /** The severity golangci assigns: the first matching `severity.rules` entry, else the default (null: the linter's own). */
    fun severity(config: GolangciConfig, relPath: String, linter: String, text: String, sourceLine: String? = null): String? {
        val path = relPath.replace('\\', '/').removePrefix("./")
        val name = GolangciLinters.reportedAs(linter, config.version)
        return config.severityRules.firstOrNull { it.rule.matches(path, name, text, sourceLine) }?.severity ?: config.defaultSeverity
    }
}
