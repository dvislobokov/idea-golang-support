package io.github.golangsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.ThreeState
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XInlineDebuggerDataCallback
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.intellij.openapi.project.DumbService
import io.github.golangsupport.run.GoDebugPsi
import io.github.golangsupport.run.GoInlineValues
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import javax.swing.Icon

/**
 * A variable, a field, an element or the result of an expression. Children come from `variables` of its reference, in pages.
 * Changed with `setVariable` (Set Value, F2): delve has no `setExpression`, and `setVariable` wants the reference of the container the
 * variable is listed in and its name there, which are known here because the plugin asked for the list itself. A result of Evaluate
 * has no container and cannot be changed.
 */
class GoValue(
    private val process: GoDebugProcess,
    name: String,
    private val value: String,
    private val type: String?,
    private val reference: Int,
    private val indexed: Int?,
    private val evaluateName: String?,
    private val frameId: Int,
    /** The reference of the list this variable came from; null for the result of an expression. */
    private val container: Int?,
) : XNamedValue(name) {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val shown = GoValuePresentation.of(type, value)
        // Debugger | Data Views | Go: integers, pointer addresses, the String() view
        val views = GoDebuggerSettings.getInstance().state
        node.setPresentation(icon(), XRegularValuePresentation(GoDataViews.render(type, shown.value, views.integerFormat, views.showPointerAddresses), shown.type), reference > 0)
        // delve cuts long strings and slices short; the whole value is one request away (the "clipboard" context of delve loads it all)
        val expression = evaluateName?.takeIf { it.isNotBlank() }
        if (views.stringView && expression != null && type != null) process.stringViews.of(expression, type, frameId)?.thenAccept { text ->
            if (text != null && !node.isObsolete) node.setPresentation(icon(), XRegularValuePresentation(text, shown.type), reference > 0)
        }
        if (expression != null && GoValuePresentation.isCut(value)) node.setFullValueEvaluator(object : XFullValueEvaluator() {
            override fun startEvaluation(callback: XFullValueEvaluationCallback) {
                process.evaluate(expression, frameId, "clipboard").whenComplete { answer, error ->
                    if (error != null) callback.errorOccurred(GoDebugProcess.errorText(error)) else callback.evaluated(answer.string("result").orEmpty())
                }
            }
        })
    }

    private fun icon(): Icon = when {
        name.startsWith("[") -> AllIcons.Debugger.Value
        type?.startsWith("func") == true -> AllIcons.Nodes.Function
        else -> AllIcons.Nodes.Variable
    }

    override fun computeChildren(node: XCompositeNode) = GoValueChildren(process, reference, indexed, frameId).load(node, 0)

    /** Add to Watches and Copy Reference take the expression of delve, not the name of the row (`[0]`, `Name`). */
    override fun calculateEvaluationExpression(): Promise<XExpression> =
        resolvedPromise(XExpressionImpl.fromText(evaluateName?.takeIf { it.isNotBlank() } ?: name))

    /** Values in the editor, next to the code ("Show values inline"): a local or a parameter tells on which lines it belongs. */
    override fun computeInlineDebuggerData(callback: XInlineDebuggerDataCallback): ThreeState {
        if (evaluateName?.takeIf { it.isNotBlank() } != name) return ThreeState.NO
        val position = process.session.currentPosition ?: return ThreeState.NO
        val document = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(position.file) }
            ?: return ThreeState.NO
        // resolve needs indices: in dumb mode, and while the document is not committed, there are no values in the editor
        val project = process.session.project
        if (DumbService.isDumb(project)) return ThreeState.NO
        val lines = GoDebugPsi.compute(project, document) { file -> GoInlineValues.lines(file, name, position.line) }.orEmpty()
        for (line in lines) XDebuggerUtil.getInstance().createPosition(position.file, line)?.let(callback::computed)
        return if (lines.isEmpty()) ThreeState.NO else ThreeState.YES
    }

    /** Delve assigns to numbers, booleans, strings and pointers; a struct or a slice as a whole it refuses, so those get no editor. */
    override fun getModifier(): XValueModifier? =
        container?.takeIf { reference <= 0 || value.startsWith("\"") || type?.startsWith("*") == true }?.let(::Modifier)

    private inner class Modifier(private val container: Int) : XValueModifier() {
        override fun calculateInitialValueEditorText(callback: XInitialValueCallback) = callback.setValue(value)

        override fun setValue(newValue: XExpression, callback: XModificationCallback) {
            process.connection.request("setVariable", setVariableArguments(container, name, newValue.expression), GoDebugProcess.REQUEST_TIMEOUT_MS)
                .whenComplete { _, error ->
                    // the views are rebuilt, the new value comes from delve
                    if (error == null) callback.valueModified() else callback.errorOccurred(GoDebugProcess.errorText(error))
                }
        }
    }

    companion object {
        fun setVariableArguments(container: Int, name: String, value: String): JsonObject =
            json("variablesReference" to container, "name" to name, "value" to value.trim())

        fun of(process: GoDebugProcess, variable: JsonObject, frameId: Int, container: Int) = GoValue(
            process, variable.string("name").orEmpty(), variable.string("value").orEmpty(), variable.string("type"),
            variable.int("variablesReference") ?: 0, variable.int("indexedVariables"), variable.string("evaluateName"), frameId, container,
        )
    }
}

/**
 * What delve prints, made readable where its raw form says little: `[]uint8 len: 5, cap: 5, [104,105]` is the text `"hi"`, an
 * `*errors.errorString {s: "boom"}` is `"boom"`. Pure text, so it is testable; `time.Time` delve formats itself.
 */
object GoValuePresentation {
    class Shown(val value: String, val type: String?)

    private val BYTES = Regex("""^\[\]uint8 len: (\d+), cap: \d+, \[([\d,]*)(,\.\.\.\+\d+ more)?]$""")
    private val ERROR_MESSAGE = Regex("""\b(?:s|msg|message|Message|text|Text): ("(?:[^"\\]|\\.)*")""")
    private val CUT = Regex("""\.\.\."?\+\d+ more|\.\.\."$""")

    fun of(type: String?, value: String): Shown {
        val kind = type?.takeIf { it.isNotBlank() }
        byteText(value)?.let { (text, length) -> return Shown(text, "[]byte len $length") }
        if (kind != null && (kind == "error" || kind.startsWith("error(") || kind.endsWith("Error") || kind.endsWith("error"))) {
            ERROR_MESSAGE.find(value)?.let { return Shown(it.groupValues[1], kind) }
        }
        return Shown(value, kind)
    }

    /** The bytes of a `[]byte` as a string, when every one of them is text; `…` when delve has not sent them all. */
    fun byteText(value: String): Pair<String, Int>? {
        val match = BYTES.matchEntire(value) ?: return null
        val length = match.groupValues[1].toInt()
        val bytes = match.groupValues[2].split(',').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        if (bytes.isEmpty()) return null
        if (bytes.any { it < 0 || it > 255 }) return null
        val text = String(ByteArray(bytes.size) { bytes[it].toByte() }, Charsets.UTF_8)
        if (text.any { it == '\uFFFD' || it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }) return null
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
        return "\"$escaped" + (if (match.groupValues[3].isNotEmpty()) "…" else "") + "\"" to length
    }

    /** `"abc..."+13 more`, `[1,2,3,...+61 more]`: delve loads 64 elements or runes and says how many it left out. */
    fun isCut(value: String): Boolean = CUT.containsMatchIn(value)
}

/** The children of a reference, a page at a time with `start` / `count`: a slice of a million elements is not read whole. "Show more" asks for the next page. */
class GoValueChildren(private val process: GoDebugProcess, private val reference: Int, private val indexed: Int?, private val frameId: Int) {
    /** [groups] (the other scopes of a frame) go below the variables of the first page. */
    fun load(node: XCompositeNode, start: Int, groups: List<XValueGroup> = emptyList()) {
        if (reference <= 0) return node.addChildren(XValueChildrenList().also { list -> groups.forEach(list::addBottomGroup) }, true)
        process.connection.request("variables", json("variablesReference" to reference, "start" to start, "count" to PAGE), GoDebugProcess.REQUEST_TIMEOUT_MS)
            .whenComplete { answer, error ->
                if (node.isObsolete) return@whenComplete
                if (error != null) return@whenComplete node.setErrorMessage(GoDebugProcess.errorText(error))
                val variables = answer.objects("variables")
                val children = XValueChildrenList(variables.size)
                variables.forEach { children.add(GoValue.of(process, it, frameId, reference)) }
                groups.forEach(children::addBottomGroup)
                val more = variables.size >= PAGE
                node.addChildren(children, !more)
                if (more) {
                    val shown = start + variables.size
                    node.tooManyChildren(indexed?.let { (it - shown).coerceAtLeast(1) } ?: PAGE) { load(node, shown) }
                }
            }
    }

    companion object {
        const val PAGE = 100
    }
}
