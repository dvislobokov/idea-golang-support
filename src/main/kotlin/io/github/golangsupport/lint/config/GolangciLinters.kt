package io.github.golangsupport.lint.config

/** A linter golangci-lint knows: its v1 presets and whether it is "fast" (syntax-only load, golangci's `fast` set). */
data class GolangciKnownLinter(val name: String, val presets: Set<String> = emptySet(), val fast: Boolean = false)

/**
 * The linter tables of golangci-lint and the sets a configuration turns on. Tables are of the pinned golangci (catalog, efac294f1005) for the
 * linters this plugin ports or maps; a linter missing from [KNOWN] can still be enabled by name, it is just not part of `all`, presets or `fast`.
 */
object GolangciLinters {
    /** v1 default set (`linters` without `enable-all` / `disable-all`). `typecheck` is the compiler, not listed. */
    val DEFAULT_V1: Set<String> = linkedSetOf("errcheck", "gosimple", "govet", "ineffassign", "staticcheck", "unused")

    /** v2 `linters.default: standard`: gosimple and stylecheck are part of staticcheck there. */
    val DEFAULT_V2: Set<String> = linkedSetOf("errcheck", "govet", "ineffassign", "staticcheck", "unused")

    /** Old names golangci still accepts (v1) and the v1 linters merged into another in v2. */
    val ALIASES: Map<String, String> = mapOf(
        "vet" to "govet", "vetshadow" to "govet", "megacheck" to "staticcheck", "gas" to "gosec", "gomnd" to "mnd", "goerr113" to "err113",
        "golint" to "revive", "exportloopref" to "copyloopvar",
    )

    /** In v2 these are checks of staticcheck, selected with `linters.settings.staticcheck.checks`. */
    val MERGED_INTO_STATICCHECK_V2: Set<String> = setOf("gosimple", "stylecheck")

    val FORMATTERS: Set<String> = setOf("gofmt", "gofumpt", "goimports", "gci", "golines", "swaggo")

    private fun l(name: String, presets: String, fast: Boolean = false) = GolangciKnownLinter(name, presets.split(' ').filter(String::isNotEmpty).toSet(), fast)

    val KNOWN: List<GolangciKnownLinter> = listOf(
        l("asasalint", "bugs"), l("asciicheck", "bugs style", true), l("bidichk", "bugs", true), l("bodyclose", "performance bugs"),
        l("canonicalheader", "style"), l("containedctx", "style"), l("contextcheck", "bugs"), l("copyloopvar", "style"), l("cyclop", "complexity"),
        l("decorder", "style", true), l("depguard", "style import module"), l("dogsled", "style", true), l("dupl", "style", true),
        l("dupword", "comment", true), l("durationcheck", "bugs"), l("err113", "style error"), l("errcheck", "bugs error"), l("errchkjson", "bugs"),
        l("errname", "style"), l("errorlint", "bugs error"), l("exhaustive", "bugs"), l("exhaustruct", "style test"), l("exptostd", "style"),
        l("fatcontext", "performance"), l("forbidigo", "style"), l("forcetypeassert", "style"), l("funlen", "complexity", true),
        l("gci", "format import", true), l("gocheckcompilerdirectives", "bugs", true), l("gochecknoglobals", "style"),
        l("gochecknoinits", "style", true), l("gocognit", "complexity", true), l("goconst", "style", true), l("gocritic", "style metalinter"),
        l("gocyclo", "complexity", true), l("godot", "style comment", true), l("godox", "style comment", true), l("gofmt", "format", true),
        l("gofumpt", "format", true), l("goheader", "style", true), l("goimports", "format import", true), l("gomoddirectives", "style module", true),
        l("gomodguard", "style import module", true), l("goprintffuncname", "style", true), l("gosec", "bugs"), l("gosimple", "style"),
        l("govet", "bugs metalinter"), l("grouper", "style", true), l("importas", "style"), l("inamedparam", "style", true),
        l("ineffassign", "unused", true), l("interfacebloat", "style", true), l("intrange", "style"), l("ireturn", "style"),
        l("lll", "style", true), l("maintidx", "complexity", true), l("makezero", "style bugs"), l("mirror", "style"), l("misspell", "style comment", true),
        l("mnd", "style"), l("musttag", "style bugs"), l("nakedret", "style", true), l("nestif", "complexity", true), l("nilerr", "bugs"),
        l("nilnil", "style"), l("nlreturn", "style", true), l("noctx", "performance bugs"), l("nolintlint", "style", true),
        l("nonamedreturns", "style", true), l("nosprintfhostport", "style", true), l("paralleltest", "style test"), l("perfsprint", "performance"),
        l("prealloc", "performance"), l("predeclared", "style", true), l("promlinter", "style", true), l("reassign", "bugs"),
        l("recvcheck", "bugs"), l("revive", "style metalinter"), l("rowserrcheck", "bugs sql"), l("sqlclosecheck", "bugs sql"),
        l("staticcheck", "bugs metalinter"), l("stylecheck", "style"), l("tagalign", "style", true), l("tagliatelle", "style", true),
        l("testableexamples", "test", true), l("testifylint", "style test"), l("testpackage", "style test", true), l("thelper", "test"),
        l("tparallel", "style test"), l("unconvert", "style"), l("unparam", "unused"), l("unused", "unused"), l("usestdlibvars", "style", true),
        l("usetesting", "test"), l("varnamelen", "style"), l("wastedassign", "style"), l("whitespace", "style", true), l("wrapcheck", "style error"),
        l("wsl", "style", true),
    )

    fun canonical(name: String): String = ALIASES[name.lowercase()] ?: name.lowercase()

    /** The golangci linter that reports a finding of a rule of [linter] (engine naming) under [version]: v2 reports gosimple/stylecheck as staticcheck. */
    fun reportedAs(linter: String, version: Int): String {
        val name = canonical(linter.substringBefore(':'))
        return if (version >= 2 && name in MERGED_INTO_STATICCHECK_V2) "staticcheck" else name
    }

    /**
     * The linters [config] turns on, in golangci's order of application: the base set ([GolangciConfig.default]), plus presets (v1) and
     * `enable`, minus `disable`, then `fast` filtering (v1). Names are canonical ([ALIASES]); in v2 `gosimple`/`stylecheck` resolve to staticcheck.
     * Formatters are not linters in v2 and are not returned; v1 returns them when enabled (they are linters there).
     */
    fun effectiveLinters(config: GolangciConfig, known: Collection<GolangciKnownLinter> = KNOWN): Set<String> {
        val v2 = config.version >= 2
        val pool = known.filter { !v2 || (it.name !in FORMATTERS && it.name !in MERGED_INTO_STATICCHECK_V2) }
        fun norm(name: String) = canonical(name).let { if (v2 && it in MERGED_INTO_STATICCHECK_V2) "staticcheck" else it }
        val result = linkedSetOf<String>()
        when (config.default) {
            GolangciDefault.STANDARD -> result += if (v2) DEFAULT_V2 else DEFAULT_V1
            GolangciDefault.ALL -> result += pool.map { it.name }
            GolangciDefault.FAST -> result += pool.filter { it.fast }.map { it.name }
            GolangciDefault.NONE -> {}
        }
        if (!v2) config.presets.forEach { preset -> result += pool.filter { preset in it.presets }.map { it.name } }
        config.enable.forEach { result += norm(it) }
        config.disable.forEach { result -= norm(it) }
        if (config.fastOnly) {
            val fast = known.filter { it.fast }.map { it.name }.toSet()
            result.retainAll(fast)
        }
        return result
    }

    // --- settings of the linters the engine ports, read the way each linter reads them ---

    /** errcheck: `exclude-functions` (`(io.Closer).Close`, `fmt.Fprintf`, regex-free names), the flags, and `ignore` of old v1 configs. */
    data class Errcheck(val excludeFunctions: List<String>, val checkTypeAssertions: Boolean, val checkBlank: Boolean, val disableDefaultExclusions: Boolean)

    fun errcheck(config: GolangciConfig): Errcheck {
        val s = config.settingsOf("errcheck")
        return Errcheck(GolangciSettings.strings(s["exclude-functions"]), GolangciSettings.bool(s["check-type-assertions"]) ?: false,
            GolangciSettings.bool(s["check-blank"]) ?: false, GolangciSettings.bool(s["disable-default-exclusions"]) ?: false)
    }

    /** One revive rule as configured: [arguments] as parsed (list of scalars or maps), [exclude] path patterns of revive. */
    data class ReviveRule(val name: String, val disabled: Boolean, val severity: String?, val arguments: List<Any?>, val exclude: List<String>)

    /** revive's default rules (its `defaults.toml`): golangci runs them when `rules` is empty and `enable-all-rules` is off. */
    val REVIVE_DEFAULTS: Set<String> = linkedSetOf(
        "blank-imports", "context-as-argument", "context-keys-type", "dot-imports", "empty-block", "error-naming", "error-return", "error-strings",
        "errorf", "exported", "increment-decrement", "indent-error-flow", "package-comments", "range", "receiver-naming", "redefines-builtin-id",
        "superfluous-else", "time-naming", "unexported-return", "unreachable-code", "unused-parameter", "var-declaration", "var-naming",
    )

    /** The configured revive rules by name; [enableAll] is `enable-all-rules` (then a listed rule with `disabled: true` is off, the rest on). */
    data class Revive(val enableAll: Boolean, val rules: Map<String, ReviveRule>, val severity: String?, val confidence: Double?) {
        /** Whether [rule] runs: listed rules unless disabled; with no list, revive's defaults; with enable-all, all but the disabled. */
        fun isEnabled(rule: String): Boolean {
            val configured = rules[rule]
            return when {
                configured != null -> !configured.disabled
                enableAll -> true
                rules.isEmpty() -> rule in REVIVE_DEFAULTS
                else -> false
            }
        }
    }

    fun revive(config: GolangciConfig): Revive {
        val s = config.settingsOf("revive")
        val rules = GolangciSettings.list(s["rules"]).mapNotNull { item ->
            val m = GolangciSettings.map(item) ?: return@mapNotNull null
            val name = m["name"]?.toString() ?: return@mapNotNull null
            ReviveRule(name, GolangciSettings.bool(m["disabled"]) ?: false, m["severity"]?.toString(), GolangciSettings.list(m["arguments"]),
                GolangciSettings.strings(m["exclude"]))
        }.associateBy { it.name }
        return Revive(GolangciSettings.bool(s["enable-all-rules"]) ?: false, rules, s["severity"]?.toString(), (s["confidence"] as? Number)?.toDouble())
    }

    /** staticcheck's default `checks` (its `DefaultConfig`) — the value golangci uses for staticcheck in v2 and for stylecheck in v1. */
    val STATICCHECK_DEFAULT_CHECKS: List<String> = listOf("all", "-ST1000", "-ST1003", "-ST1016", "-ST1020", "-ST1021", "-ST1022")

    /**
     * Whether staticcheck-family check [id] (`SA4006`, `S1000`, `ST1005`, `QF1001`) runs: its linter is on ([enabled], from [effectiveLinters]) and
     * the `checks` patterns select it. v1: SA* — `staticcheck`, S* — `gosimple`, ST* — `stylecheck` (each with its own `checks`), QF* never run;
     * v2: all of them — `staticcheck` and `linters.settings.staticcheck.checks`.
     */
    fun isStaticcheckEnabled(config: GolangciConfig, enabled: Set<String>, id: String): Boolean {
        val linter = if (config.version >= 2) "staticcheck" else when {
            id.startsWith("SA") -> "staticcheck"
            id.startsWith("ST") -> "stylecheck"
            id.startsWith("QF") -> return false
            id.startsWith("S") -> "gosimple"
            else -> return false
        }
        if (linter !in enabled) return false
        val configured = GolangciSettings.strings(config.settingsOf(linter)["checks"])
        val patterns = configured.ifEmpty { if (config.version >= 2 || linter == "stylecheck") STATICCHECK_DEFAULT_CHECKS else listOf("*") }
        return checkSelected(patterns, id)
    }

    /** staticcheck's `FilterChecks`: patterns in order, the last match wins; `all`/`*`, globs (`SA1*`), `-` negates, `inherit` keeps defaults. */
    fun checkSelected(patterns: List<String>, id: String): Boolean {
        var on = false
        for (raw in patterns) {
            val p = raw.trim()
            if (p == "inherit") { on = checkSelected(STATICCHECK_DEFAULT_CHECKS, id); continue }
            val negate = p.startsWith("-")
            val glob = p.removePrefix("-")
            val hit = glob == "all" || glob == "*" || if (glob.endsWith("*")) id.startsWith(glob.dropLast(1)) else glob.equals(id, ignoreCase = true)
            if (hit) on = !negate
        }
        return on
    }

    /** go vet's default analyzers (golangci govet default: these unless `enable-all` / `disable-all`). */
    val GOVET_DEFAULTS: Set<String> = linkedSetOf(
        "appends", "asmdecl", "assign", "atomic", "bools", "buildtag", "cgocall", "composites", "copylocks", "defers", "directive", "errorsas",
        "framepointer", "hostport", "httpresponse", "ifaceassert", "loopclosure", "lostcancel", "nilfunc", "printf", "shift", "sigchanyzer", "slog",
        "stdmethods", "stdversion", "stringintconv", "structtag", "testinggoroutine", "tests", "timeformat", "unmarshal", "unreachable",
        "unsafeptr", "unusedresult", "waitgroup",
    )

    /** govet analyzers off by default in golangci. */
    val GOVET_EXTRA: Set<String> = linkedSetOf("atomicalign", "deepequalerrors", "fieldalignment", "findcall", "httpmux", "nilness", "reflectvaluecompare", "shadow", "sortslice", "unusedwrite")

    /** Whether govet [analyzer] runs (govet itself must be on): `enable-all`/`disable-all`, then `enable`, then `disable`; v1 `check-shadowing` too. */
    fun isGovetAnalyzerEnabled(config: GolangciConfig, analyzer: String): Boolean {
        val s = config.settingsOf("govet")
        var on = when {
            GolangciSettings.bool(s["enable-all"]) == true -> true
            GolangciSettings.bool(s["disable-all"]) == true -> false
            else -> analyzer in GOVET_DEFAULTS
        }
        if (analyzer == "shadow" && GolangciSettings.bool(s["check-shadowing"]) == true) on = true
        if (analyzer in GolangciSettings.strings(s["enable"])) on = true
        if (analyzer in GolangciSettings.strings(s["disable"])) on = false
        return on
    }

    /** Options of one govet analyzer: `settings.<analyzer>` (`printf: {funcs: [...]}`, `shadow: {strict: true}`). */
    fun govetAnalyzerSettings(config: GolangciConfig, analyzer: String): Map<String, Any?> =
        GolangciSettings.map(GolangciSettings.map(config.settingsOf("govet")["settings"])?.get(analyzer)).orEmpty()

    /** gocritic's checks enabled by default (non-experimental, non-opinionated diagnostic and style checks of the pinned v0.15.0). */
    val GOCRITIC_DEFAULTS: Set<String> = linkedSetOf(
        "appendAssign", "argOrder", "assignOp", "badCall", "badCond", "captLocal", "caseOrder", "codegenComment", "commentFormatting",
        "defaultCaseOrder", "deprecatedComment", "dupArg", "dupBranchBody", "dupCase", "dupSubExpr", "elseif", "exitAfterDefer", "flagDeref",
        "flagName", "ifElseChain", "mapKey", "newDeref", "offBy1", "regexpMust", "singleCaseSwitch", "sloppyLen", "sloppyTypeAssert",
        "switchTrue", "typeSwitchVar", "underef", "unlambda", "unslice", "valSwap", "wrapperFunc",
    )

    /**
     * Whether gocritic [check] runs, by names only: `enable-all` / `disable-all`, `enabled-checks`, `disabled-checks` (case-insensitive, as gocritic
     * compares). Tags (`enabled-tags`) need the checker's tags, which the engine knows: pass them as [tags].
     */
    fun isGocriticEnabled(config: GolangciConfig, check: String, tags: Set<String> = emptySet()): Boolean {
        val s = config.settingsOf("gocritic")
        fun has(key: String, value: String) = GolangciSettings.strings(s[key]).any { it.equals(value, ignoreCase = true) }
        if (has("disabled-checks", check)) return false
        if (has("enabled-checks", check)) return true
        if (tags.any { has("disabled-tags", it) }) return false
        if (tags.any { has("enabled-tags", it) }) return true
        return when {
            GolangciSettings.bool(s["enable-all"]) == true -> true
            GolangciSettings.bool(s["disable-all"]) == true -> false
            else -> GOCRITIC_DEFAULTS.any { it.equals(check, ignoreCase = true) }
        }
    }

    /** Per-checker options of gocritic: `settings.<checkerLowercase>` (`hugeParam: {sizeThreshold: 80}`), keys as gocritic lowercases them. */
    fun gocriticSettings(config: GolangciConfig, check: String): Map<String, Any?> {
        val all = GolangciSettings.map(config.settingsOf("gocritic")["settings"]).orEmpty()
        return GolangciSettings.map(all.entries.firstOrNull { it.key.equals(check, ignoreCase = true) }?.value).orEmpty()
    }

    /** gosec: `includes` / `excludes` of G-rule ids (empty includes = all), `config` per rule. */
    fun isGosecRuleEnabled(config: GolangciConfig, rule: String): Boolean {
        val s = config.settingsOf("gosec")
        val includes = GolangciSettings.strings(s["includes"])
        return (includes.isEmpty() || rule in includes) && rule !in GolangciSettings.strings(s["excludes"])
    }
}

/** Typed reads of parsed settings: values come from YAML/JSON as String / Number / Boolean / List / Map. */
object GolangciSettings {
    fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.lowercase().toBooleanStrictOrNull()
        else -> null
    }

    fun int(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    fun list(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is List<*> -> value
        else -> listOf(value)
    }

    fun strings(value: Any?): List<String> = list(value).mapNotNull { it?.toString() }

    @Suppress("UNCHECKED_CAST")
    fun map(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    /** A nested value by a dotted path: `at(settings, "settings.printf.funcs")`. */
    fun at(root: Map<String, Any?>, path: String): Any? = path.split('.').fold(root as Any?) { node, key -> map(node)?.get(key) }
}
