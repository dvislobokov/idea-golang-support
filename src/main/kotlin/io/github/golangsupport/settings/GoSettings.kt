package io.github.golangsupport.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.execution.ParametersListUtil

enum class GoFormatter(val title: String) {
    GOFMT("gofmt"),
    GOIMPORTS("goimports"),

    /** `golangci-lint fmt --stdin` (v2): the formatters of `.golangci.yml` (gofumpt, goimports, gci, golines...) in one go. */
    GOLANGCI_LINT_FMT("golangci-lint fmt"),

    /** Reformat Code is left to the language server, or to nobody. */
    NONE("None");

    override fun toString(): String = title
}

/** Machine-wide settings of the plugin: where the Go toolchain and its tools are, and what the plugin does on its own. */
@Service(Service.Level.APP)
@State(name = "GoSupportSettings", storages = [Storage("golang-support.xml")])
class GoSettings : SimplePersistentStateComponent<GoSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        /** Empty: the executable is looked up on PATH and in the default installation directories. */
        var goPath by string("")

        /** Name of a tool -> its executable; a tool without an entry is looked up on PATH, in GOBIN and in GOPATH/bin. */
        var toolPaths by map<String, String>()

        /** `-tags` of every command that compiles: build, run, test, vet, the language server, the linter, the debugger. */
        var buildTags by string("")

        var languageServerEnabled by property(true)
        /** On: the analyzers of staticcheck come with fixes, and fixes are what Alt+Enter is made of here. */
        var goplsStaticcheck by property(true)
        var goplsGofumpt by property(false)
        var goplsInlayHints by property(true)
        /** The usages of the name at the caret and, on `func` or `return`, the exit points of the function: highlighted by gopls. */
        var goplsHighlightUsages by property(true)

        /** Setting of gopls -> its value as a JSON text, from the page with the catalogue of the server; wins over the switches above. */
        var goplsOverrides by map<String, String>()

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
        /** `//` above a declaration becomes `// Name `. */
        var docCommentNames by property(true)
        /** A Cyrillic letter typed in code is typed as the Latin character of its key. */
        var latinInCode by property(true)
        /** Extract variable, Inline call, Add test... of gopls as items of Alt+Enter. */
        var goplsActionsInMenu by property(true)
        var lintOnTheFly by property(true)

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
    }

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

    var goPath: String
        get() = state.goPath.orEmpty()
        set(value) { state.goPath = value.trim() }

    var buildTags: String
        get() = state.buildTags.orEmpty()
        set(value) { state.buildTags = value.trim() }

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

    var formatter: GoFormatter
        get() = state.formatter
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

    var lintOnTheFly: Boolean
        get() = state.lintOnTheFly
        set(value) { state.lintOnTheFly = value }

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

    companion object {
        fun getInstance(): GoSettings = service()
    }
}
