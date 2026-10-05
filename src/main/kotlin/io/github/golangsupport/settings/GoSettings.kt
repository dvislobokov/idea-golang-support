package io.github.golangsupport.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.execution.ParametersListUtil
import io.github.golangsupport.GoBundle
import io.github.golangsupport.PluginLanguage
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.project.impl.GoLibraryRootsMode

/** Where delve builds the binary of a debug session. */
enum class GoDebugBinaryLocation(val title: String) {
    /** The way GoLand does it: nothing is left in the project. */
    TEMP("System temp directory"),

    /** The way `dlv debug` does it: next to the sources, `__debug_bin...` in the directory of the package. */
    PACKAGE("Package directory"),

    /** [GoSettings.debugBinaryDirectory]; a relative path is from the directory of the package. */
    CUSTOM("Custom directory");

    /** What the combo shows; [toString] is what the settings file keeps, so it stays English. */
    val label: String get() = GoBundle.messageOr("debugger.binaryLocation.$name", title)

    override fun toString(): String = title
}

/** Who serves a feature: the language server or the plugin itself; see [io.github.golangsupport.lang.GoFeatures]. */
enum class GoFeatureSource(val title: String) {
    GOPLS("gopls"),
    NATIVE("Built-in");

    /** What a combo shows; [toString] is what the settings file keeps, so it stays English. */
    val label: String get() = GoBundle.messageOr("featureSource.$name", title)

    override fun toString(): String = title
}

/** Which Go sources outside the project the native PSI indexes as library roots ([io.github.golangsupport.sdk.GoIgsLibraryRootsPolicy]). */
enum class GoLibraryRoots(val mode: GoLibraryRootsMode, val title: String) {
    STANDARD_LIBRARY(GoLibraryRootsMode.STANDARD_LIBRARY, "Standard library"),
    STANDARD_LIBRARY_AND_DEPENDENCIES(GoLibraryRootsMode.STANDARD_LIBRARY_AND_DEPENDENCIES, "Standard library and dependencies");

    /** What the combo shows; [toString] is what the settings file keeps, so it stays English. */
    val label: String get() = GoBundle.messageOr("libraryRoots.$name", title)

    override fun toString(): String = title
}

/** Whether the `cgo` build tag holds for the analysis and `CGO_ENABLED` of the go commands: what `go env` says, or forced. */
enum class GoCgoMode(val title: String, val forced: Boolean?) {
    DEFAULT("Default", null),
    ENABLED("Enabled", true),
    DISABLED("Disabled", false);

    val label: String get() = GoBundle.messageOr("buildTags.cgo.$name", title)

    override fun toString(): String = title
}

enum class GoFormatter(val title: String) {
    GOFMT("gofmt"),
    GOIMPORTS("goimports"),

    /** `golangci-lint fmt --stdin` (v2): the formatters of `.golangci.yml` (gofumpt, goimports, gci, golines...) in one go. */
    GOLANGCI_LINT_FMT("golangci-lint fmt"),

    /** Reformat Code is left to the language server, or to nobody. */
    NONE("None"),

    /** The gofmt-compatible formatter of go-psi-ide (`lang.formatter`): no process, the platform engine formats (MIGRATION.md step 8j). */
    NATIVE("Built-in");

    /** An external tool over the text of the editor, run by [io.github.golangsupport.format.GoFormattingService]. */
    val isExternalTool: Boolean get() = this != NONE && this != NATIVE

    override fun toString(): String = title
}

/** When a custom linter runs: at a save made in this session only, or at every highlighting pass of a saved file (opening it too). */
enum class GoLinterTrigger(val title: String) {
    ON_SAVE("On save"),
    AFTER_SAVE("On the fly, saved files");

    /** What the table shows; [toString] is what the settings file keeps, so it stays English. */
    val label: String get() = GoBundle.messageOr("lint.trigger.$name", title)

    override fun toString(): String = title
}

/** The working directory of a custom linter: the root of the module of the file (`.golangci.yml` and go.mod are found from there) or the file's own directory. */
enum class GoLinterDirectory(val title: String) {
    MODULE_ROOT("Module root"),
    FILE_DIRECTORY("File directory");

    val label: String get() = GoBundle.messageOr("lint.directory.$name", title)

    override fun toString(): String = title
}

/** The report a custom linter prints on stdout. */
enum class GoLinterFormat(val title: String) {
    /** `{"Issues":[{"FromLinter":…,"Text":…,"Pos":{"Filename":…,"Line":…,"Column":…}}]}`, v1 and v2 alike. */
    GOLANGCI_JSON("golangci JSON"),
    SARIF("SARIF 2.1.0");

    val label: String get() = GoBundle.messageOr("lint.format.$name", title)

    override fun toString(): String = title
}

/** One row of Custom linters; a bean with a no-argument constructor and mutable fields, as the settings serializer and the table want it. */
@com.intellij.util.xmlb.annotations.Tag("linter")
data class GoCustomLinter(
    var name: String = "",
    var commandLine: String = "",
    var enabled: Boolean = true,
    var trigger: GoLinterTrigger = GoLinterTrigger.AFTER_SAVE,
    var directory: GoLinterDirectory = GoLinterDirectory.MODULE_ROOT,
    var format: GoLinterFormat = GoLinterFormat.GOLANGCI_JSON,
) {
    val isRunnable: Boolean get() = enabled && name.isNotBlank() && commandLine.isNotBlank()
}

/** Machine-wide settings of the plugin: where the Go toolchain and its tools are, and what the plugin does on its own. */
@Service(Service.Level.APP)
@State(name = "GoSupportSettings", storages = [Storage("golang-support.xml")])
class GoSettings : SimplePersistentStateComponent<GoSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        /** Empty: the executable is looked up on PATH and in the default installation directories. */
        var language by enum(PluginLanguage.AUTO)
        var goPath by string("")

        /** Name of a tool -> its executable; a tool without an entry is looked up on PATH, in GOBIN and in GOPATH/bin. */
        var toolPaths by map<String, String>()

        /** `-tags` of every command that compiles: build, run, test, vet, the language server, the linter, the debugger. */
        var buildTags by string("")

        /** GOOS / GOARCH the code is analysed for (which files a package has, which `//go:build` lines hold); empty: the ones of the machine. */
        var analysisGoos by string("")
        var analysisGoarch by string("")

        /** The `cgo` tag of the analysis and `CGO_ENABLED` of the go commands; DEFAULT: what `go env` says. */
        var cgoMode by enum(GoCgoMode.DEFAULT)
        /** GOEXPERIMENT of the analysis (`goexperiment.X` tags) and of the go commands; empty: the environment's. */
        var goExperiments by string("")

        /** Where the plugin keeps the programs it builds and installs, its catalogue and temporary builds; empty: the system directory of the IDE. */
        var pluginDataDirectory by string("")

        /** The library roots of the native PSI: `$GOROOT/src` alone, or also the module directories of the build list. */
        var libraryRoots by enum(GoLibraryRoots.STANDARD_LIBRARY_AND_DEPENDENCIES)

        /** Off by default since 0.2.82: the built-in analysis serves every feature, and gopls is not even started; turning it on brings it back. */
        var languageServerEnabled by property(false)
        /** On: the analyzers of staticcheck come with fixes, and fixes are what Alt+Enter is made of here. */
        var goplsStaticcheck by property(true)
        var goplsGofumpt by property(false)
        var goplsInlayHints by property(true)
        /** The usages of the name at the caret and, on `func` or `return`, the exit points of the function: highlighted by gopls. */
        var goplsHighlightUsages by property(true)

        /** Setting of gopls -> its value as a JSON text, from the page with the catalogue of the server; wins over the switches above. */
        var goplsOverrides by map<String, String>()

        // who answers in the editor while the plugin moves from gopls to its own PSI: one switch for every feature but formatting
        var languageFeaturesSource by enum(GoFeatureSource.NATIVE)

        /** `-rpc.trace`: every message of the protocol in the log window of gopls. Big; for looking into what the server was asked. */
        var goplsTrace by property(false)
        /** `-debug=localhost:0`: the web pages of gopls about its sessions, memory and metrics (menu Go | gopls | Open Debug Pages). */
        var goplsDebugPages by property(false)

        var formatter by enum(GoFormatter.GOFMT)

        /** On: unformatted Go passes no review, so the formatter is not something to remember about. */
        var formatOnSave by property(true)
        var createRunConfigurations by property(true)

        /** `if err != nil { ... }` and the `defer` of what was just opened, as grey text to accept with Tab. */
        var inlineIdioms by property(true)
        /** `make([]string, 0, len(keys))` after `arr := `, the arguments of a call, the values of `return`: grey text from the context. */
        var inlineSuggestions by property(true)
        /** The grey text of both in the colours of the code, muted towards the background; off: the plain grey of the platform. */
        var inlineSuggestionColors by property(true)
        /** `nil, err` as one item of the completion list after `return`. */
        var completeReturnValues by property(true)
        /** Names that begin with what is typed go above the fuzzy matches of gopls. */
        var completionPrefixFirst by property(true)
        /** Values of the type the code wants: bold, and the only ones in smart completion. */
        var completionByType by property(true)
        /** After a completed call: its parameters, and the list for an argument. */
        var completionArguments by property(true)
        /** `http` of `net/http` in the list before it is imported; the import is written when it is chosen. */
        var completionUnimportedPackages by property(true)
        /** `http.Client` chosen where a value is expected becomes `http.Client{}`. */
        var completionStructBraces by property(true)
        /** `Printl` gives `fmt.Println`: the names of the standard library and of the modules of go.mod. */
        var completionCatalogue by property(true)
        /** `type Name struct {...}`, `for i, x := range xs` as items of the list where a keyword can begin. */
        var completionKeywordTemplates by property(true)
        /** `404` → `http.StatusNotFound` where a status goes; the layouts of `time.Parse` / `Format` and their parts inside the string. */
        var completionValues by property(true)
        /** `//` above a declaration becomes `// Name `. */
        var docCommentNames by property(true)
        /** A Cyrillic letter typed in code is typed as the Latin character of its key. */
        var latinInCode by property(true)
        /** Extract variable, Inline call, Add test... of gopls as items of Alt+Enter. */
        var goplsActionsInMenu by property(true)
        /**
         * golangci-lint in the editor and as a formatter. Off by default since 2026-10-03: the native inspections are the analysis of the
         * plugin, and nothing in the default setup runs golangci-lint or asks to install it. The old `lintOnTheFly` was on by default and
         * so never written down: nobody has an explicit "on" to carry over, and the new switch starts off for everyone.
         */
        var golangciLint by property(false)
        /** govulncheck in the background (at project open, after go.mod / go.sum change, hourly at most): off by default, it downloads the vulnerability database. */
        var vulnerabilityCheck by property(false)

        /** Linters of the user whose report is the JSON of golangci-lint or SARIF ([io.github.golangsupport.lint.GoCustomLinters]). */
        var customLinters by list<GoCustomLinter>()

        /** How long golangci-lint and a custom linter may run for one file, in seconds. */
        var lintTimeoutSeconds by property(90)

        /** Every Go and go.mod problem of the project in the Project Errors tab of Problems, kept up to date in the background. */
        var projectAnalysis by property(true)
        /** Off: only errors go to the Project Errors tab. */
        var projectAnalysisWarnings by property(true)

        /** Added to every `go test`: `-race -count=1`. */
        var testArguments by string("")

        // the debugger: launch attributes of delve
        var debugShowGlobalVariables by property(false)
        var debugHideSystemGoroutines by property(true)
        var debugStackTraceDepth by property(50)

        /** `--check-go-version=false`: a delve newer than the toolchain refuses the program otherwise, and Debug does nothing at all. */
        var debugAnyGoVersion by property(true)

        /** On by default while the debugger is young: a session that went wrong cannot be logged afterwards. */
        var debugAdapterLog by property(true)

        var debugBinaryLocation by enum(GoDebugBinaryLocation.TEMP)

        /** [GoDebugBinaryLocation.CUSTOM]: the directory, a relative path is from the package directory. */
        var debugBinaryDirectory by string("")

        /** Dump Goroutines through a delve attach (the program runs on) rather than SIGQUIT (the program prints the dump and exits); Windows has no SIGQUIT. */
        var debugDumpViaDelve by property(com.intellij.openapi.util.SystemInfo.isWindows)

        /** Optimize Imports over the Go files being saved, apart from Reformat; Settings | Tools | Actions on Save shows it too. Off, as in GoLand. */
        var optimizeImportsOnSave by property(false)
        /** `-mod=vendor` / `-mod=mod` (through GOFLAGS) of the go commands of a module that has `vendor/modules.txt`; AUTO: what go decides. */
        var vendoring by enum(GoVendoring.AUTO)
        /** `go mod download` after go.mod is saved with other requirements, for every project but the exceptions ([GoModDownloads]). On, as in GoLand. */
        var downloadDependencies by property(true)
        /** `GOPROXY=…;GOPRIVATE=…`: added to the environment of the go commands of the plugin (Settings | Go | Go Modules). */
        var modulesEnvironment by string("")
    }

    var optimizeImportsOnSave: Boolean
        get() = state.optimizeImportsOnSave
        set(value) { state.optimizeImportsOnSave = value }

    var vendoring: GoVendoring
        get() = state.vendoring
        set(value) { state.vendoring = value }

    var downloadDependencies: Boolean
        get() = state.downloadDependencies
        set(value) { state.downloadDependencies = value }

    var modulesEnvironment: String
        get() = state.modulesEnvironment.orEmpty()
        set(value) { state.modulesEnvironment = value.trim() }

    var debugShowGlobalVariables: Boolean
        get() = state.debugShowGlobalVariables
        set(value) { state.debugShowGlobalVariables = value }

    var debugHideSystemGoroutines: Boolean
        get() = state.debugHideSystemGoroutines
        set(value) { state.debugHideSystemGoroutines = value }

    var debugStackTraceDepth: Int
        get() = state.debugStackTraceDepth
        set(value) { state.debugStackTraceDepth = value.coerceIn(1, 1000) }

    var debugAnyGoVersion: Boolean
        get() = state.debugAnyGoVersion
        set(value) { state.debugAnyGoVersion = value }

    var debugAdapterLog: Boolean
        get() = state.debugAdapterLog
        set(value) { state.debugAdapterLog = value }

    var debugBinaryLocation: GoDebugBinaryLocation
        get() = state.debugBinaryLocation
        set(value) { state.debugBinaryLocation = value }

    var debugBinaryDirectory: String
        get() = state.debugBinaryDirectory.orEmpty()
        set(value) { state.debugBinaryDirectory = value.trim() }

    /** The directory delve is to build the binary of a session into, for the package in [packageDirectory]; null is the temp directory of the system. */
    fun debugBinaryDirectory(packageDirectory: String): String? = when (debugBinaryLocation) {
        GoDebugBinaryLocation.TEMP -> null
        GoDebugBinaryLocation.PACKAGE -> packageDirectory
        GoDebugBinaryLocation.CUSTOM -> debugBinaryDirectory.takeIf { it.isNotBlank() }?.let { java.io.File(packageDirectory).resolve(it).path } ?: packageDirectory
    }

    /** The language of the settings pages; the rest of the plugin speaks the language of the IDE. */
    var language: PluginLanguage
        get() = state.language
        set(value) { state.language = value }

    var goPath: String
        get() = state.goPath.orEmpty()
        set(value) { state.goPath = value.trim() }

    var buildTags: String
        get() = state.buildTags.orEmpty()
        set(value) { state.buildTags = value.trim() }

    var analysisGoos: String
        get() = state.analysisGoos.orEmpty()
        set(value) { state.analysisGoos = value.trim() }

    var analysisGoarch: String
        get() = state.analysisGoarch.orEmpty()
        set(value) { state.analysisGoarch = value.trim() }

    var cgoMode: GoCgoMode
        get() = state.cgoMode
        set(value) { state.cgoMode = value }

    /** As typed, without spaces: `rangefunc,noaliastypeparams`. */
    var goExperiments: String
        get() = state.goExperiments.orEmpty()
        set(value) { state.goExperiments = normalizeExperiments(value) }

    var libraryRoots: GoLibraryRoots
        get() = state.libraryRoots
        set(value) { state.libraryRoots = value }

    var pluginDataDirectory: String
        get() = state.pluginDataDirectory ?: ""
        set(value) { state.pluginDataDirectory = value }

    var languageServerEnabled: Boolean
        get() = state.languageServerEnabled
        set(value) { state.languageServerEnabled = value }

    var goplsHighlightUsages: Boolean
        get() = state.goplsHighlightUsages
        set(value) { state.goplsHighlightUsages = value }

    var goplsStaticcheck: Boolean
        get() = state.goplsStaticcheck
        set(value) { state.goplsStaticcheck = value }

    var goplsGofumpt: Boolean
        get() = state.goplsGofumpt
        set(value) { state.goplsGofumpt = value }

    var goplsInlayHints: Boolean
        get() = state.goplsInlayHints
        set(value) { state.goplsInlayHints = value }

    var goplsTrace: Boolean
        get() = state.goplsTrace
        set(value) { state.goplsTrace = value }

    var goplsDebugPages: Boolean
        get() = state.goplsDebugPages
        set(value) { state.goplsDebugPages = value }

    var goplsOverrides: Map<String, String>
        get() = state.goplsOverrides.toMap()
        // a new map: BaseState does not notice changes inside the one it holds
        set(value) { state.goplsOverrides = value.toMutableMap() }

    /** gopls or the native PSI for every language feature at once; the formatter has its own choice ([formatter]). */
    var languageFeaturesSource: GoFeatureSource
        get() = state.languageFeaturesSource
        set(value) { state.languageFeaturesSource = value }

    /** The source of [feature]: [languageFeaturesSource] for all but formatting, which follows [formatter]: the plugin formats (a tool or the Built-in formatter) unless the formatter is left to the language server. */
    fun featureSource(feature: GoFeature): GoFeatureSource = when (feature) {
        GoFeature.FORMATTING -> if (formatter == GoFormatter.NONE) GoFeatureSource.GOPLS else GoFeatureSource.NATIVE
        else -> languageFeaturesSource
    }

    /** `golangci-lint fmt` only while golangci-lint is on: with it off nothing may start golangci-lint, so the choice falls back to gofmt. */
    var formatter: GoFormatter
        get() = state.formatter.let { if (it == GoFormatter.GOLANGCI_LINT_FMT && !golangciLint) GoFormatter.GOFMT else it }
        set(value) { state.formatter = value }

    var docCommentNames: Boolean
        get() = state.docCommentNames
        set(value) { state.docCommentNames = value }

    var formatOnSave: Boolean
        get() = state.formatOnSave
        set(value) { state.formatOnSave = value }

    var inlineIdioms: Boolean
        get() = state.inlineIdioms
        set(value) { state.inlineIdioms = value }

    var inlineSuggestions: Boolean
        get() = state.inlineSuggestions
        set(value) { state.inlineSuggestions = value }

    var inlineSuggestionColors: Boolean
        get() = state.inlineSuggestionColors
        set(value) { state.inlineSuggestionColors = value }

    var completeReturnValues: Boolean
        get() = state.completeReturnValues
        set(value) { state.completeReturnValues = value }

    var completionPrefixFirst: Boolean
        get() = state.completionPrefixFirst
        set(value) { state.completionPrefixFirst = value }

    var completionByType: Boolean
        get() = state.completionByType
        set(value) { state.completionByType = value }

    var completionArguments: Boolean
        get() = state.completionArguments
        set(value) { state.completionArguments = value }

    var completionUnimportedPackages: Boolean
        get() = state.completionUnimportedPackages
        set(value) { state.completionUnimportedPackages = value }

    var completionStructBraces: Boolean
        get() = state.completionStructBraces
        set(value) { state.completionStructBraces = value }

    var completionCatalogue: Boolean
        get() = state.completionCatalogue
        set(value) { state.completionCatalogue = value }

    var completionValues: Boolean
        get() = state.completionValues
        set(value) { state.completionValues = value }

    var completionKeywordTemplates: Boolean
        get() = state.completionKeywordTemplates
        set(value) { state.completionKeywordTemplates = value }

    var latinInCode: Boolean
        get() = state.latinInCode
        set(value) { state.latinInCode = value }

    var goplsActionsInMenu: Boolean
        get() = state.goplsActionsInMenu
        set(value) { state.goplsActionsInMenu = value }

    var createRunConfigurations: Boolean
        get() = state.createRunConfigurations
        set(value) { state.createRunConfigurations = value }

    var golangciLint: Boolean
        get() = state.golangciLint
        set(value) { state.golangciLint = value }

    var vulnerabilityCheck: Boolean
        get() = state.vulnerabilityCheck
        set(value) { state.vulnerabilityCheck = value }

    /** Copies: the table of the settings page edits its own rows, and BaseState notices only a new list. */
    var customLinters: List<GoCustomLinter>
        get() = state.customLinters.map { it.copy() }
        set(value) { state.customLinters = value.map { it.copy() }.toMutableList() }

    var lintTimeoutSeconds: Int
        get() = state.lintTimeoutSeconds
        set(value) { state.lintTimeoutSeconds = value.coerceIn(5, 600) }

    var projectAnalysis: Boolean
        get() = state.projectAnalysis
        set(value) { state.projectAnalysis = value }

    var projectAnalysisWarnings: Boolean
        get() = state.projectAnalysisWarnings
        set(value) { state.projectAnalysisWarnings = value }

    var debugDumpViaDelve: Boolean
        get() = state.debugDumpViaDelve
        set(value) { state.debugDumpViaDelve = value }

    var testArguments: String
        get() = state.testArguments.orEmpty()
        set(value) { state.testArguments = value.trim() }

    fun toolPath(name: String): String = state.toolPaths[name].orEmpty()

    fun setToolPath(name: String, path: String) {
        // a new map: BaseState does not notice changes inside the one it holds
        state.toolPaths = state.toolPaths.toMutableMap().apply { if (path.isBlank()) remove(name) else put(name, path.trim()) }
    }

    /** `-tags=a,b` for the `go` command, nothing without tags; spaces and commas both separate, as people type them. */
    fun buildTagArguments(): List<String> = tagList().takeIf { it.isNotEmpty() }?.let { listOf("-tags=" + it.joinToString(",")) }.orEmpty()

    fun tagList(): List<String> = buildTags.split(',', ' ').filter { it.isNotBlank() }

    fun testArgumentList(): List<String> = ParametersListUtil.parse(testArguments)

    /** `CGO_ENABLED` / `GOEXPERIMENT` the go commands of the plugin get from Cgo support and Experiments; nothing for the defaults. */
    fun goCommandEnvironment(): Map<String, String> = buildMap {
        cgoMode.forced?.let { put("CGO_ENABLED", if (it) "1" else "0") }
        goExperiments.takeIf { it.isNotEmpty() }?.let { put("GOEXPERIMENT", it) }
    }

    companion object {
        fun getInstance(): GoSettings = service()

        private val EXPERIMENT = Regex("[A-Za-z0-9_]+")

        fun normalizeExperiments(text: String): String = text.split(',', ' ').map(String::trim).filter(String::isNotEmpty).joinToString(",")

        /** The first malformed name of a GOEXPERIMENT list, or null: names only (letters, digits, `_`), `no` before one turns it off. Not checked against a toolchain. */
        fun invalidExperiment(text: String): String? = text.split(',', ' ').map(String::trim).filter(String::isNotEmpty).firstOrNull { !EXPERIMENT.matches(it) }
    }
}

/** "Enable vendoring support" of Settings | Go | Go Modules: the `-mod` flag of the go commands of a module with a `vendor/modules.txt`. */
enum class GoVendoring(val title: String, private val flag: String?) {
    /** go's own rule: vendor mode when `vendor/modules.txt` is there and go.mod says go 1.14 or later. */
    AUTO("Automatically", null),
    ALWAYS("Always", "-mod=vendor"),
    NEVER("Never", "-mod=mod");

    val label: String get() = GoBundle.messageOr("modules.vendoring.$name", title)

    /** The flag for a module that has (or has not) a vendor directory: without one `-mod=vendor` would fail, and `-mod=mod` changes nothing. */
    fun modFlag(hasVendor: Boolean): String? = flag?.takeIf { hasVendor }

    override fun toString(): String = title
}
