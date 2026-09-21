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
        var goplsStaticcheck by property(false)
        var goplsGofumpt by property(false)
        var goplsInlayHints by property(true)

        var formatter by enum(GoFormatter.GOFMT)
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

    var goplsStaticcheck: Boolean
        get() = state.goplsStaticcheck
        set(value) { state.goplsStaticcheck = value }

    var goplsGofumpt: Boolean
        get() = state.goplsGofumpt
        set(value) { state.goplsGofumpt = value }

    var goplsInlayHints: Boolean
        get() = state.goplsInlayHints
        set(value) { state.goplsInlayHints = value }

    var formatter: GoFormatter
        get() = state.formatter
        set(value) { state.formatter = value }

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
