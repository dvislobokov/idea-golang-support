package io.github.golangsupport.dap

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.run.BreakpointExtras
import io.github.golangsupport.run.GoBreakpointLines
import io.github.golangsupport.run.GoPanicFilter
import io.github.golangsupport.run.HitCondition
import javax.swing.Icon
import javax.swing.JComponent

/** What delve can do with a line breakpoint and the DAP client of the platform has no place for. */
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

/** Line breakpoints in `.go` files; registered with the debugger, because without one they would stop nothing. */
class GoLineBreakpointType : XLineBreakpointType<GoLineBreakpointProperties>("go-line", "Go Line Breakpoints") {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): GoLineBreakpointProperties = GoLineBreakpointProperties()

    /** For a breakpoint read from the workspace file: without it the saved hit count and log message are dropped on load. */
    override fun createProperties(): GoLineBreakpointProperties = GoLineBreakpointProperties()

    /** With an editor for expressions the platform shows "Condition" for the breakpoint; the condition itself is sent by its DAP client. */
    override fun getEditorsProvider(breakpoint: XLineBreakpoint<GoLineBreakpointProperties>, project: Project): XDebuggerEditorsProvider = GoEditorsProvider()

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileType != GoFileType) return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        // asked for every line the mouse passes in the gutter: one scan per change of the text
        val cached = document.getUserData(LINES)?.takeIf { it.first == document.modificationStamp }
            ?: (document.modificationStamp to GoBreakpointLines.find(document.immutableCharSequence)).also { document.putUserData(LINES, it) }
        return line in cached.second
    }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<XLineBreakpoint<GoLineBreakpointProperties>> = PropertiesPanel()

    /** Hit count and log message: sent to delve with the breakpoint, see [extrasAt]. */
    private class PropertiesPanel : XBreakpointCustomPropertiesPanel<XLineBreakpoint<GoLineBreakpointProperties>>() {
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

        override fun loadFrom(breakpoint: XLineBreakpoint<GoLineBreakpointProperties>) {
            hitCondition.text = breakpoint.properties?.hitCondition.orEmpty()
            logMessage.text = breakpoint.properties?.logMessage.orEmpty()
        }

        override fun saveTo(breakpoint: XLineBreakpoint<GoLineBreakpointProperties>) {
            val properties = breakpoint.properties ?: return
            // an invalid hit count is not kept: delve would refuse the whole breakpoint
            val hits = hitCondition.text.trim().takeIf { HitCondition.isValid(it) }.orEmpty()
            val message = logMessage.text.trim()
            if (hits == properties.hitCondition && message == properties.logMessage) return
            properties.hitCondition = hits
            properties.logMessage = message
            // the platform re-registers a breakpoint whose properties have changed: that is what sends it to the adapter again
            (breakpoint as? com.intellij.xdebugger.impl.breakpoints.XBreakpointBase<*, *, *>)?.fireBreakpointChanged()
        }
    }

    companion object {
        private val LINES: Key<Pair<Long, Set<Int>>> = Key.create("io.github.golangsupport.dap.breakpointLines")

        /** The extras of the breakpoint of the IDE at [path] and the 1-based [line] of the protocol; null when there is none (Run to Cursor). */
        fun extrasAt(project: Project, path: String, line: Int): BreakpointExtras? = ReadAction.compute<BreakpointExtras?, RuntimeException> {
            if (project.isDisposed) return@compute null
            val type = EXTENSION_POINT_NAME.findExtension(GoLineBreakpointType::class.java) ?: return@compute null
            XDebuggerManager.getInstance(project).breakpointManager.getBreakpoints(type)
                // the URL, not presentableFilePath: that one is relative to the project
                .firstOrNull { it.line == line - 1 && FileUtil.pathsEqual(VfsUtilCore.urlToPath(it.fileUrl), path) }
                ?.properties?.let { BreakpointExtras(it.hitCondition, it.logMessage) }
        }
    }
}

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

class GoPanicBreakpointHandler(private val session: DapDebugSession) : XBreakpointHandler<GoPanicBreakpoint>(GoPanicBreakpointType::class.java) {
    /** What was registered, to take away exactly that: the properties may have changed since. */
    private val registered = HashMap<GoPanicBreakpoint, List<DapExceptionBreakpoint>>()

    override fun registerBreakpoint(breakpoint: GoPanicBreakpoint) {
        val filters = breakpoint.properties?.filters.orEmpty().map { DapExceptionBreakpoint.create(it.id, null, breakpoint) }
        synchronized(registered) { registered[breakpoint] = filters }
        session.commandProcessor.submitCommand { with(session.breakpointManager) { filters.forEach { addExceptionBreakpoint(it) } } }
    }

    override fun unregisterBreakpoint(breakpoint: GoPanicBreakpoint, temporary: Boolean) {
        val filters = synchronized(registered) { registered.remove(breakpoint) } ?: return
        session.commandProcessor.submitCommand { with(session.breakpointManager) { filters.forEach { removeExceptionBreakpoint(it) } } }
    }
}
