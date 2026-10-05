package io.github.golangsupport.debugger

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.settings.DebuggerSettingsCategory
import com.intellij.xdebugger.settings.XDebuggerSettings
import io.github.golangsupport.GoBundle
import java.math.BigInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** "Show integers as" of Settings | Build, Execution, Deployment | Debugger | Data Views | Go. */
enum class GoIntegerFormat(val title: String) {
    /** As delve prints them: decimal, with `= 0x…` after an unsigned value. */
    DECIMAL("Decimal"),
    HEXADECIMAL("Hexadecimal"),
    BINARY("Binary"),
    BOTH("Decimal and hexadecimal");

    val label: String get() = GoBundle.messageOr("dataViews.integers.$name", title)

    override fun toString(): String = title
}

/**
 * The Go tab of Debugger | Data Views, the way GoLand has it (`dlv.dataViews`): the format of integers, the addresses of pointers and the
 * String() view. Stored by the platform with the other debugger settings (`debugger.xml`).
 */
class GoDebuggerSettings : XDebuggerSettings<GoDebuggerSettings.State>("go") {
    class State {
        var integerFormat: GoIntegerFormat = GoIntegerFormat.DECIMAL
        var showPointerAddresses: Boolean = true

        /** Off by default, as in GoLand: every shown value of a type with String() costs a function call in the stopped program. */
        var stringView: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    override fun createConfigurables(category: DebuggerSettingsCategory): Collection<Configurable> =
        if (category == DebuggerSettingsCategory.DATA_VIEWS) listOf(GoDataViewsConfigurable(this)) else emptyList()

    companion object {
        fun getInstance(): GoDebuggerSettings = getInstance(GoDebuggerSettings::class.java)
    }
}

private class GoDataViewsConfigurable(private val settings: GoDebuggerSettings) : BoundConfigurable(GoBundle.message("dataViews.title")) {
    override fun createPanel(): DialogPanel = panel {
        row(GoBundle.message("dataViews.integers")) {
            // the renderer, not toString(): what the settings file keeps has to stay English whatever the language of the page
            comboBox(GoIntegerFormat.entries, SimpleListCellRenderer.create("") { it.label })
                .bindItem({ settings.state.integerFormat }, { it?.let { format -> settings.state.integerFormat = format } })
                .comment(GoBundle.message("dataViews.integers.comment"))
        }
        row {
            checkBox(GoBundle.message("dataViews.pointers")).bindSelected({ settings.state.showPointerAddresses }, { settings.state.showPointerAddresses = it })
                .comment(GoBundle.message("dataViews.pointers.comment"))
        }
        row {
            checkBox(GoBundle.message("dataViews.stringView")).bindSelected({ settings.state.stringView }, { settings.state.stringView = it })
                .comment(GoBundle.message("dataViews.stringView.comment"))
        }
    }
}

/** The text of a value as Data Views | Go wants it, from what delve sent. Pure, for the tests. */
object GoDataViews {
    private val INTEGER_TYPES = setOf("int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "byte", "rune")

    /** Delve's text of every unsigned integer, named types too: `42 = 0x2a`. */
    private val UNSIGNED = Regex("""^(\d+) = 0x[0-9a-fA-F]+$""")
    private val SIGNED = Regex("""^-?\d+$""")

    /** `(*main.Node)(0xc000012345)`: a pointer delve did not follow, also inside a struct or a slice. */
    private val POINTER = Regex("""\(("[^"]*"|[^()"]+)\)\(0x[0-9a-fA-F]+\)""")

    /** `error(*errors.errorString) 0xc000012345`: the data of an interface delve did not load. */
    private val INTERFACE_ADDRESS = Regex("""^(\S+\(.*\)) 0x[0-9a-fA-F]+$""")

    fun render(type: String?, value: String, integers: GoIntegerFormat, pointerAddresses: Boolean): String {
        val number = integer(type, value, integers)
        if (number != null || pointerAddresses || value.startsWith("\"")) return number ?: value
        val withoutPointers = POINTER.replace(value) { it.groupValues[1].removeSurrounding("\"") }
        return INTERFACE_ADDRESS.matchEntire(withoutPointers)?.let { it.groupValues[1] + " …" } ?: withoutPointers
    }

    /** An integer in [format]; null when [value] is no integer (a float of a round value delve prints as `1` too: the type decides). */
    fun integer(type: String?, value: String, format: GoIntegerFormat): String? {
        val decimal = UNSIGNED.matchEntire(value)?.groupValues?.get(1) ?: value.takeIf { type in INTEGER_TYPES && SIGNED.matches(it) } ?: return null
        val number = BigInteger(decimal)
        return when (format) {
            GoIntegerFormat.DECIMAL -> value
            GoIntegerFormat.HEXADECIMAL -> radix(number, 16, "0x")
            GoIntegerFormat.BINARY -> radix(number, 2, "0b")
            GoIntegerFormat.BOTH -> "$decimal = ${radix(number, 16, "0x")}"
        }
    }

    private fun radix(number: BigInteger, radix: Int, prefix: String): String =
        if (number.signum() < 0) "-$prefix" + number.negate().toString(radix) else prefix + number.toString(radix)

    /**
     * Whether a value of [type] may have a String() to show: a named type (`main.Color`, `*store.Order`), not a slice, map, channel or
     * function of one; `time.Time` delve formats itself.
     */
    fun stringViewCandidate(type: String?): Boolean {
        val named = type?.trim()?.trimStart('*') ?: return false
        if (named.isEmpty() || named.startsWith("[") || named.startsWith("map[") || named.startsWith("chan ") || named.startsWith("func(")) return false
        return '.' in named && named != "time.Time"
    }

    /** Delve calls a function only with `call`; the parentheses keep `p.items[0]` or `*p` one operand. */
    fun stringCall(expression: String): String = "call ($expression).String()"

    /** The answer of delve for a type without the method: no use asking again for another value of it. */
    fun isNoStringMethod(error: String): Boolean = "has no member String" in error
}

/**
 * The String() view of one debug session: `call (x).String()` in the top frame of the stopped goroutine (delve calls functions nowhere
 * else), one call per expression and stop; a type without String() is not asked again in the session.
 */
class GoStringViews(private val process: GoDebugProcess) {
    @Volatile private var topFrameId: Int? = null
    private val results = ConcurrentHashMap<String, CompletableFuture<String?>>()
    private val withoutString = ConcurrentHashMap.newKeySet<String>()

    /** A new stop: the values of the last one are gone, the frame ids too. */
    fun stopped(topFrame: Int?) {
        topFrameId = topFrame
        results.clear()
    }

    /** The result of String() as delve shows a string (`"…"`), or null; no future when the value is not to be asked about. */
    fun of(expression: String, type: String, frameId: Int): CompletableFuture<String?>? {
        if (frameId != topFrameId || type in withoutString || !GoDataViews.stringViewCandidate(type)) return null
        return results.computeIfAbsent(expression) {
            process.evaluate(GoDataViews.stringCall(expression), frameId, "watch").handle { answer, error ->
                if (error != null) {
                    if (GoDataViews.isNoStringMethod(GoDebugProcess.errorText(error))) withoutString += type
                    null
                } else answer.string("result")?.takeIf { answer.string("type") == "string" }
            }
        }
    }
}
