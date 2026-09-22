package io.github.golangsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.run.GoBreakpointLines
import io.github.golangsupport.run.GoPanicFilter
import io.github.golangsupport.run.HitCondition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent

// --- line breakpoints ---

/** What delve can do with a line breakpoint beyond a condition: stop on a hit count, log instead of stopping. */
class GoLineBreakpointProperties : XBreakpointProperties<GoLineBreakpointProperties.State>() {
    class State {
        @JvmField var hitCondition: String = ""
        @JvmField var logMessage: String = ""
    }

    private var state = State()

    var hitCondition: String
        get() = state.hitCondition
        set(value) { state.hitCondition = value }

    var logMessage: String
        get() = state.logMessage
        set(value) { state.logMessage = value }

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
}

typealias GoLineBreakpoint = XLineBreakpoint<GoLineBreakpointProperties>

/** Line breakpoints in `.go` files. The id is the one of the breakpoints users have saved: it must not change. */
class GoLineBreakpointType : XLineBreakpointType<GoLineBreakpointProperties>("go-line", "Go Line Breakpoints") {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): GoLineBreakpointProperties = GoLineBreakpointProperties()

    /** For a breakpoint read from the workspace file: without it the saved hit count and log message are dropped on load. */
    override fun createProperties(): GoLineBreakpointProperties = GoLineBreakpointProperties()

    /** With an editor for expressions the platform shows "Condition" for the breakpoint; the condition goes to delve with it. */
    override fun getEditorsProvider(breakpoint: GoLineBreakpoint, project: Project): XDebuggerEditorsProvider = GoEditorsProvider()

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileType != GoFileType) return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        // asked for every line the mouse passes in the gutter: one scan per change of the text
        val cached = document.getUserData(LINES)?.takeIf { it.first == document.modificationStamp }
            ?: (document.modificationStamp to GoBreakpointLines.find(document.immutableCharSequence)).also { document.putUserData(LINES, it) }
        return line in cached.second
    }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<GoLineBreakpoint> = PropertiesPanel()

    private class PropertiesPanel : XBreakpointCustomPropertiesPanel<GoLineBreakpoint>() {
        private val hitCondition = JBTextField().apply { emptyText.text = "Every hit" }
        private val logMessage = JBTextField().apply { emptyText.text = "Stop, do not log" }

        override fun getComponent(): JComponent = panel {
            row("Hit count:") {
                cell(hitCondition).align(AlignX.FILL).comment("Stop on the 5th hit: <code>5</code>; from the 3rd on: <code>&gt;= 3</code>; every 10th: <code>% 10</code>")
                    .validationOnInput { if (HitCondition.isValid(it.text)) null else error("A positive number, optionally after ==, !=, >=, >, <=, < or %") }
            }
            row("Log message:") {
                cell(logMessage).align(AlignX.FILL).comment("Printed to the debug console instead of stopping; <code>{expression}</code> is replaced by its value: <code>total = {total}</code>")
            }
        }

        override fun loadFrom(breakpoint: GoLineBreakpoint) {
            hitCondition.text = breakpoint.properties?.hitCondition.orEmpty()
            logMessage.text = breakpoint.properties?.logMessage.orEmpty()
        }

        override fun saveTo(breakpoint: GoLineBreakpoint) {
            val properties = breakpoint.properties ?: return
            // an invalid hit count is not kept: delve would refuse the whole breakpoint
            val hits = hitCondition.text.trim().takeIf { HitCondition.isValid(it) }.orEmpty()
            val message = logMessage.text.trim()
            if (hits == properties.hitCondition && message == properties.logMessage) return
            properties.hitCondition = hits
            properties.logMessage = message
            // a breakpoint whose properties change is registered again: that is what sends it to delve again
            (breakpoint as? com.intellij.xdebugger.impl.breakpoints.XBreakpointBase<*, *, *>)?.fireBreakpointChanged()
        }
    }

    private companion object {
        val LINES: Key<Pair<Long, Set<Int>>> = Key.create("io.github.golangsupport.debugger.breakpointLines")
    }
}

/**
 * The line breakpoints of a session: `setBreakpoints` is per file and replaces the whole list of the file, so every change sends the file
 * again. The condition, the hit count and the log message go with each breakpoint. Delve answers in the order it was sent: that is how
 * its ids are matched to the breakpoints of the IDE.
 */
class GoLineBreakpointHandler(private val process: GoDebugProcess) : XBreakpointHandler<GoLineBreakpoint>(GoLineBreakpointType::class.java) {
    private val byFile = ConcurrentHashMap<String, MutableSet<GoLineBreakpoint>>()
    private val byId = ConcurrentHashMap<Int, GoLineBreakpoint>()
    /** Run to Cursor: file -> the 0-based line of a breakpoint of one stop. */
    private val temporary = ConcurrentHashMap<String, Int>()

    override fun registerBreakpoint(breakpoint: GoLineBreakpoint) {
        val path = GoDebugProcess.pathOf(breakpoint.fileUrl)
        byFile.computeIfAbsent(path) { ConcurrentHashMap.newKeySet() }.add(breakpoint)
        if (process.configured) send(path)
    }

    override fun unregisterBreakpoint(breakpoint: GoLineBreakpoint, temporary: Boolean) {
        val path = GoDebugProcess.pathOf(breakpoint.fileUrl)
        byFile[path]?.remove(breakpoint)
        if (process.configured) send(path)
    }

    fun sendAll(): CompletableFuture<*> = CompletableFuture.allOf(*(byFile.keys + temporary.keys).distinct().map(::send).toTypedArray())

    fun find(id: Int): GoLineBreakpoint? = byId[id]

    fun runTo(path: String, line: Int): CompletableFuture<*> {
        temporary[path] = line
        return send(path)
    }

    fun clearTemporary() {
        val paths = temporary.keys.toList()
        temporary.clear()
        paths.forEach(::send)
    }

    /** `breakpoint` event: delve has verified (or moved, or refused) a breakpoint after the fact. */
    fun update(breakpoint: JsonObject) {
        val ide = breakpoint.int("id")?.let(byId::get) ?: return
        mark(ide, breakpoint)
    }

    private fun send(path: String): CompletableFuture<*> {
        val breakpoints = byFile[path].orEmpty().filter { it.isEnabled }.sortedBy { it.line }
        val extra = temporary[path]?.takeIf { line -> breakpoints.none { it.line == line } }
        val list = breakpoints.map { breakpointJson(it.line, it.conditionExpression?.expression, it.properties?.hitCondition, it.properties?.logMessage) } +
            listOfNotNull(extra?.let { breakpointJson(it, null, null, null) })
        val source = json("path" to path, "name" to path.substringAfterLast('/').substringAfterLast('\\'))
        return process.connection.request("setBreakpoints", json("source" to source, "breakpoints" to list), GoDebugProcess.REQUEST_TIMEOUT_MS)
            .thenAccept { answer ->
                answer.objects("breakpoints").zip(breakpoints).forEach { (dap, ide) ->
                    dap.int("id")?.let { byId[it] = ide }
                    mark(ide, dap)
                }
            }
            .exceptionally { error -> breakpoints.forEach { process.session.setBreakpointInvalid(it, GoDebugProcess.errorText(error)) }; null }
    }

    private fun mark(ide: GoLineBreakpoint, dap: JsonObject) {
        if (dap.bool("verified") == true) process.session.setBreakpointVerified(ide)
        else process.session.setBreakpointInvalid(ide, dap.string("message") ?: "The debugger has not bound the breakpoint (yet)")
    }

    companion object {
        /** One breakpoint of `setBreakpoints`: the line of the protocol is 1-based; the empty parts are left out. */
        fun breakpointJson(line: Int, condition: String?, hitCondition: String?, logMessage: String?): Map<String, Any> = buildMap {
            put("line", line + 1)
            condition?.trim()?.takeIf { it.isNotEmpty() }?.let { put("condition", it) }
            HitCondition.normalize(hitCondition)?.let { put("hitCondition", it) }
            logMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { put("logMessage", it) }
        }
    }
}

// --- panic breakpoints ---

/** Which of the two stops of delve at a dying program are wanted; both by default, as delve itself has them. */
class GoPanicBreakpointProperties : XBreakpointProperties<GoPanicBreakpointProperties.State>() {
    class State {
        @JvmField var unrecoveredPanic: Boolean = true
        @JvmField var fatalThrow: Boolean = true
    }

    private var state = State()

    var filters: Set<GoPanicFilter>
        get() = buildSet {
            if (state.unrecoveredPanic) add(GoPanicFilter.UNRECOVERED_PANIC)
            if (state.fatalThrow) add(GoPanicFilter.FATAL_THROW)
        }
        set(value) {
            state.unrecoveredPanic = GoPanicFilter.UNRECOVERED_PANIC in value
            state.fatalThrow = GoPanicFilter.FATAL_THROW in value
        }

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
}

typealias GoPanicBreakpoint = XBreakpoint<GoPanicBreakpointProperties>

/** Go has no exceptions to break on; what delve offers under that name of the protocol is a stop where the program is about to die. */
class GoPanicBreakpointType : XBreakpointType<GoPanicBreakpoint, GoPanicBreakpointProperties>("go-panic", "Go Panic Breakpoints") {
    override fun getDisplayText(breakpoint: GoPanicBreakpoint): String =
        breakpoint.properties?.filters?.joinToString(", ") { it.title }?.ifEmpty { null } ?: "No panics"

    override fun createProperties(): GoPanicBreakpointProperties = GoPanicBreakpointProperties()
    override fun getEnabledIcon(): Icon = AllIcons.Debugger.Db_exception_breakpoint
    override fun getDisabledIcon(): Icon = AllIcons.Debugger.Db_disabled_exception_breakpoint

    /** The platform makes default breakpoints disabled; this one is what stops at a crash, so it is on from the start. */
    override fun createDefaultBreakpoint(creator: XBreakpointCreator<GoPanicBreakpointProperties>): GoPanicBreakpoint = creator.createBreakpoint(createProperties()).apply { isEnabled = true }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<GoPanicBreakpoint> = object : XBreakpointCustomPropertiesPanel<GoPanicBreakpoint>() {
        private val boxes = GoPanicFilter.entries.associateWith { JBCheckBox(it.title) }

        override fun getComponent(): JComponent = panel { row("Break at:") { boxes.values.forEach { cell(it) } } }
        override fun loadFrom(breakpoint: GoPanicBreakpoint) = boxes.forEach { (filter, box) -> box.isSelected = filter in breakpoint.properties?.filters.orEmpty() }
        override fun saveTo(breakpoint: GoPanicBreakpoint) {
            breakpoint.properties?.filters = boxes.filterValues { it.isSelected }.keys
        }
    }
}

/**
 * The panic breakpoints of a session as one `setExceptionBreakpoints` with the filters of delve (`unrecovered-panic`, `runtime-fatal-throw`).
 * Sent even when empty: delve that has heard nothing applies its defaults and would stop although the breakpoint is off.
 */
class GoPanicBreakpointHandler(private val process: GoDebugProcess) : XBreakpointHandler<GoPanicBreakpoint>(GoPanicBreakpointType::class.java) {
    private val registered = ConcurrentHashMap.newKeySet<GoPanicBreakpoint>()

    override fun registerBreakpoint(breakpoint: GoPanicBreakpoint) {
        registered.add(breakpoint)
        if (process.configured) send()
    }

    override fun unregisterBreakpoint(breakpoint: GoPanicBreakpoint, temporary: Boolean) {
        registered.remove(breakpoint)
        if (process.configured) send()
    }

    /** The breakpoint of the IDE to report a stop at a panic with. */
    fun first(): GoPanicBreakpoint? = registered.firstOrNull()

    fun send(): CompletableFuture<*> =
        process.connection.request("setExceptionBreakpoints", arguments(registered.flatMap { it.properties?.filters.orEmpty() }.toSet()), GoDebugProcess.REQUEST_TIMEOUT_MS)
            .exceptionally { null }

    companion object {
        fun arguments(filters: Set<GoPanicFilter>): JsonObject = json("filters" to GoPanicFilter.entries.filter { it in filters }.map { it.id })
    }
}
