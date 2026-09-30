package io.github.golangsupport.debugger

import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.run.HitCondition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A breakpoint on a function by its name, the way delve names it: `main.main`, `store.NewOrder`, `store.(*Order).Total`,
 * `net/http.(*Server).Serve`, also `name:line` and `file.go:line`. A regular expression, which the console of delve takes, its DAP
 * refuses ("could not be parsed as a function", seen live). No file and line: it survives edits and needs no source in the editor. Sent with `setFunctionBreakpoints`; the condition and the hit count go with it, as for a line breakpoint.
 * GoLand does not describe such breakpoints in its help.
 */
class GoFunctionBreakpointProperties : XBreakpointProperties<GoFunctionBreakpointProperties.State>() {
    class State {
        @JvmField var functionName: String = ""
        @JvmField var hitCondition: String = ""
    }

    private var state = State()

    var functionName: String
        get() = state.functionName
        set(value) { state.functionName = value }

    var hitCondition: String
        get() = state.hitCondition
        set(value) { state.hitCondition = value }

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
}

typealias GoFunctionBreakpoint = XBreakpoint<GoFunctionBreakpointProperties>

class GoFunctionBreakpointType : XBreakpointType<GoFunctionBreakpoint, GoFunctionBreakpointProperties>("go-function", "Go Function Breakpoints") {
    override fun getDisplayText(breakpoint: GoFunctionBreakpoint): String = breakpoint.properties?.functionName?.ifBlank { null } ?: "No function"
    override fun createProperties(): GoFunctionBreakpointProperties = GoFunctionBreakpointProperties()
    override fun getEnabledIcon(): Icon = AllIcons.Debugger.Db_method_breakpoint
    override fun getDisabledIcon(): Icon = AllIcons.Debugger.Db_disabled_method_breakpoint
    override fun getEditorsProvider(breakpoint: GoFunctionBreakpoint, project: Project): XDebuggerEditorsProvider = GoEditorsProvider()

    /** The "+" of the Breakpoints dialog: the name is asked for, the breakpoint is made. */
    override fun isAddBreakpointButtonVisible(): Boolean = true

    override fun addBreakpoint(project: Project, parentComponent: JComponent?): GoFunctionBreakpoint? {
        val name = Messages.showInputDialog(project, "Function, as delve names it: main.main, store.(*Order).Total, net/http.(*Server).Serve", "Go Function Breakpoint", null) ?: return null
        return add(project, name)
    }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<GoFunctionBreakpoint> = object : XBreakpointCustomPropertiesPanel<GoFunctionBreakpoint>() {
        private val functionName = JBTextField()
        private val hitCondition = JBTextField().apply { emptyText.text = "Every hit" }

        override fun getComponent(): JComponent = panel {
            row("Function:") { cell(functionName).align(AlignX.FILL).comment("<code>main.main</code>, <code>store.(*Order).Total</code>, <code>net/http.(*Server).Serve</code>; also <code>name:line</code> and <code>file.go:line</code>") }
            row("Hit count:") {
                cell(hitCondition).align(AlignX.FILL).comment("Stop on the 5th hit: <code>5</code>; from the 3rd on: <code>&gt;= 3</code>; every 10th: <code>% 10</code>")
                    .validationOnInput { if (HitCondition.isValid(it.text)) null else error("A positive number, optionally after ==, !=, >=, >, <=, < or %") }
            }
        }

        override fun loadFrom(breakpoint: GoFunctionBreakpoint) {
            functionName.text = breakpoint.properties?.functionName.orEmpty()
            hitCondition.text = breakpoint.properties?.hitCondition.orEmpty()
        }

        override fun saveTo(breakpoint: GoFunctionBreakpoint) {
            val properties = breakpoint.properties ?: return
            val name = functionName.text.trim()
            val hits = hitCondition.text.trim().takeIf { HitCondition.isValid(it) }.orEmpty()
            if (name == properties.functionName && hits == properties.hitCondition) return
            properties.functionName = name
            properties.hitCondition = hits
            // a breakpoint whose properties change is registered again: that is what sends it to delve again
            (breakpoint as? XBreakpointBase<*, *, *>)?.fireBreakpointChanged()
        }
    }

    companion object {
        fun add(project: Project, functionName: String): GoFunctionBreakpoint? {
            val name = functionName.trim().ifEmpty { return null }
            val type = XBreakpointType.EXTENSION_POINT_NAME.findExtension(GoFunctionBreakpointType::class.java) ?: return null
            return WriteAction.compute<GoFunctionBreakpoint, RuntimeException> {
                XDebuggerManager.getInstance(project).breakpointManager.addBreakpoint(type, GoFunctionBreakpointProperties().apply { this.functionName = name })
            }
        }
    }
}

/** The function breakpoints of a session: `setFunctionBreakpoints` replaces the whole list, so every change sends all of them. */
class GoFunctionBreakpointHandler(private val process: GoDebugProcess) : XBreakpointHandler<GoFunctionBreakpoint>(GoFunctionBreakpointType::class.java) {
    private val registered = ConcurrentHashMap.newKeySet<GoFunctionBreakpoint>()
    private val byId = ConcurrentHashMap<Int, GoFunctionBreakpoint>()

    override fun registerBreakpoint(breakpoint: GoFunctionBreakpoint) {
        registered.add(breakpoint)
        if (process.configured) send()
    }

    override fun unregisterBreakpoint(breakpoint: GoFunctionBreakpoint, temporary: Boolean) {
        registered.remove(breakpoint)
        if (process.configured) send()
    }

    fun find(id: Int): GoFunctionBreakpoint? = byId[id]

    fun send(): CompletableFuture<*> {
        val breakpoints = registered.filter { it.isEnabled && !it.properties?.functionName.isNullOrBlank() }.sortedBy { it.properties?.functionName }
        val list = breakpoints.map { breakpointJson(it.properties!!.functionName, it.conditionExpression?.expression, it.properties?.hitCondition) }
        return process.connection.request("setFunctionBreakpoints", json("breakpoints" to list), GoDebugProcess.REQUEST_TIMEOUT_MS)
            .thenAccept { answer ->
                // a breakpoint that is not on a line has no verified mark in the IDE: what delve says about it goes to the console
                answer.objects("breakpoints").zip(breakpoints).forEach { (dap, ide) ->
                    dap.int("id")?.let { byId[it] = ide }
                    if (dap.bool("verified") != true) process.print("Function breakpoint ${ide.properties?.functionName}: ${dap.string("message") ?: "not found"}\n", ProcessOutputTypes.STDERR)
                }
            }
            .exceptionally { error -> process.print("Function breakpoints: ${GoDebugProcess.errorText(error)}\n", ProcessOutputTypes.STDERR); null }
    }

    companion object {
        fun breakpointJson(name: String, condition: String?, hitCondition: String?): Map<String, Any> = buildMap {
            put("name", name.trim())
            condition?.trim()?.takeIf { it.isNotEmpty() }?.let { put("condition", it) }
            HitCondition.normalize(hitCondition)?.let { put("hitCondition", it) }
        }
    }
}

/** The name delve gives the function at a place of a file: `store.(*Order).Total`, `store.NewOrder`, `main.main`. */
object GoFunctionNames {
    fun at(text: CharSequence, offset: Int): String? {
        val structure = GoDeclarations.scan(text)
        val function = structure.declarations.filter { it.kind == GoDeclarationKind.FUNCTION || it.kind == GoDeclarationKind.METHOD }
            .lastOrNull { offset >= it.range.startOffset && offset <= it.range.endOffset } ?: return null
        val pkg = structure.packageName ?: return null
        val receiver = function.receiver ?: return "$pkg.${function.name}"
        // the pointer is in the text of the receiver, which the scanner reduces to the name of the type
        val pointer = text.subSequence(function.range.startOffset, function.nameRange.startOffset).contains('*')
        return "$pkg.${if (pointer) "(*$receiver)" else receiver}.${function.name}"
    }
}

/** Go | Debugger | Add Function Breakpoint…: the name is asked for, filled with the function at the caret. */
class GoAddFunctionBreakpointAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val initial = if (editor != null && e.getData(CommonDataKeys.PSI_FILE) is GoFile) GoFunctionNames.at(editor.document.immutableCharSequence, editor.caretModel.offset) else null
        val name = Messages.showInputDialog(project, "Function, as delve names it: main.main, store.(*Order).Total, net/http.(*Server).Serve", "Go Function Breakpoint", null, initial, null) ?: return
        GoFunctionBreakpointType.add(project, name)
    }
}
