package io.github.golangsupport.dap

import com.intellij.execution.ExecutionResult
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapEventConsumer
import com.intellij.platform.dap.DapStackFrame
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DapThread
import com.intellij.platform.dap.DapVariable
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.xdebugger.AbstractDapXValue
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.platform.dap.xdebugger.DapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DapXSuspendContext
import com.intellij.platform.dap.xdebugger.DefaultDapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.run.DapVariableContainers
import io.github.golangsupport.run.GoEvaluate
import io.github.golangsupport.run.GoHoverExpression
import javax.swing.Icon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.debug.SetVariableArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArgumentsReason
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException

/** The debug process of the platform with what it lacks for Go: the right goroutine on a stop, values on hover, panic breakpoints. */
class GoDebugProcess(
    session: XDebugSession, dapDebugSession: DapDebugSession, xDebugProcessScope: CoroutineScope, globalScope: CoroutineScope,
    debugAdapterDescriptor: DebugAdapterDescriptor<*>, executionEnvironment: ExecutionEnvironment, executionResult: ExecutionResult?,
    startRequestType: DapStartRequest, startRequestArguments: Map<String, Any?>, stopped: StoppedThread, containers: DapVariableContainers,
) : DapXDebugProcess(
    session, dapDebugSession, xDebugProcessScope, globalScope, debugAdapterDescriptor, executionEnvironment, executionResult, startRequestType, startRequestArguments,
) {
    /** Go fragments instead of the plain text of the platform: highlighting in Evaluate, watches and conditions. */
    private val editors = GoEditorsProvider()

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editors

    override val presentationFactory: DapXDebuggerPresentationFactory = GoPresentationFactory(stopped, containers)

    /** The handler of line breakpoints is the one of the platform; exception breakpoints it leaves to the debugger. */
    private val handlers: Array<XBreakpointHandler<*>> by lazy { arrayOf(*super.getBreakpointHandlers(), GoPanicBreakpointHandler(dapDebugSession)) }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = handlers
}

/**
 * On `stopped` the platform looks the stopped thread up with a binary search by id in the list the adapter has answered `threads` with,
 * and does not sort the list (found in the .NET sibling of this plugin). The threads of delve are goroutines, in no order of ids, so the
 * search misses and the active thread becomes the first paused one: some parked goroutine. The id is taken from the event itself.
 */
class GoPresentationFactory(private val stopped: StoppedThread, private val containers: DapVariableContainers) : DefaultDapXDebuggerPresentationFactory() {
    override fun createSuspendContext(commandProcessor: DapCommandProcessor, threads: List<DapThread>, activeThread: DapThread?): DapXSuspendContext =
        super.createSuspendContext(commandProcessor, threads, StoppedThread.choose(threads.map { it to it.id }, stopped.id) ?: activeThread)

    override fun createStackFrame(commandProcessor: DapCommandProcessor, thread: DapThread, frame: DapStackFrame): XStackFrame = GoStackFrame(this, commandProcessor, thread, frame)

    override fun createValue(commandProcessor: DapCommandProcessor, variable: DapVariable, icon: Icon?): XNamedValue = GoValue(this, commandProcessor, variable, icon, containers)
}

/**
 * A value of the variables view that can be changed (Set Value, F2). The value of the platform (`DefaultDapXValue`, final) has no
 * modifier: its client does not know `setVariable`. Delve has no `setExpression`, and `setVariable` wants the container of the
 * variable, which [DapVariableContainers] has seen go by. Looks exactly like the value of the platform.
 */
class GoValue(
    factory: DapXDebuggerPresentationFactory, commandProcessor: DapCommandProcessor, variable: DapVariable, icon: Icon?, private val containers: DapVariableContainers,
) : AbstractDapXValue(factory, commandProcessor, variable, icon) {

    override fun createValuePresentation(variable: DapVariable, hasChildren: Boolean, isLazy: Boolean): XValuePresentation =
        if (variable.value.isNotEmpty()) XRegularValuePresentation(variable.value, variable.type) else XRegularValuePresentation("", variable.type, "")

    /** Delve assigns to numbers, booleans, strings and pointers; a struct or a slice as a whole it refuses, so those get no editor. */
    override fun getModifier(): XValueModifier? =
        containers.of(variable.evaluateName)?.takeIf { !it.hasChildren || variable.value.startsWith("\"") || variable.type?.startsWith("*") == true }?.let(::Modifier)

    private inner class Modifier(private val container: DapVariableContainers.Container) : XValueModifier() {
        override fun calculateInitialValueEditorText(callback: XInitialValueCallback) = callback.setValue(variable.value)

        override fun setValue(newValue: XExpression, callback: XModificationCallback) {
            commandProcessor.submitCommand {
                try {
                    server.setVariable(SetVariableArguments().apply {
                        variablesReference = container.variablesReference
                        name = container.name
                        value = newValue.expression.trim()
                    }).await()
                    callback.valueModified() // the views are rebuilt, the new value comes from delve
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    callback.errorOccurred(errorText(e))
                }
            }
        }
    }

    companion object {
        /** The text of delve as it is, not the class of the exception around it. */
        fun errorText(e: Throwable): String {
            val cause = generateSequence(e) { it.cause }.firstOrNull { it is ResponseErrorException } ?: e
            return (cause as? ResponseErrorException)?.responseError?.message ?: cause.message ?: cause.javaClass.simpleName
        }
    }
}

/** The frame of the platform with an evaluator that knows what is under the mouse, see [HoverEvaluator]. */
class GoStackFrame(factory: DapXDebuggerPresentationFactory, commandProcessor: DapCommandProcessor, thread: DapThread, frame: DapStackFrame) :
    DefaultDapXStackFrame(factory, commandProcessor, thread, frame) {
    private val hoverEvaluator by lazy { HoverEvaluator(super.getEvaluator()) }

    override fun getEvaluator(): XDebuggerEvaluator = hoverEvaluator
}

/**
 * The evaluator of the platform evaluates and nothing else: asked which expression is under the mouse it answers "none", so resting
 * the mouse on a variable in the editor shows nothing. The class is final, hence a wrapper; the expression is found by tokens.
 */
class HoverEvaluator(private val delegate: XDebuggerEvaluator) : XDebuggerEvaluator() {
    /** `order.Total()` goes to delve as `call order.Total()`, see [GoEvaluate]. */
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        delegate.evaluate(GoEvaluate.expression(expression), callback, expressionPosition)

    override fun evaluate(expression: XExpression, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        val text = GoEvaluate.expression(expression.expression)
        val actual = if (text == expression.expression) expression else XDebuggerUtil.getInstance().createExpression(text, expression.language, expression.customInfo, expression.mode)
        delegate.evaluate(actual, callback, expressionPosition)
    }

    override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? =
        GoHoverExpression.rangeAt(document.immutableCharSequence, offset)
}

class GoEditorsProvider : XDebuggerEditorsProviderBase() {
    override fun getFileType(): FileType = GoFileType

    override fun createExpressionCodeFragment(project: Project, text: String, context: PsiElement?, isPhysical: Boolean): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("expression.go", GoFileType, text, LocalTimeCounter.currentTime(), isPhysical)
}

/** The thread of the last `stopped` event: written when the event arrives, before the platform starts processing it. */
class StoppedThread {
    @Volatile var id: Int? = null

    /** The consumer of the platform with [id] kept up to date; everything is passed on untouched. */
    fun recording(consumer: DapEventConsumer): DapEventConsumer = object : DapEventConsumer by consumer {
        override fun stopped(args: StoppedEventArguments?) {
            id = args?.threadId
            consumer.stopped(args?.let(::forPlatform))
        }
    }

    /**
     * A stop at an exception with `allThreadsStopped` makes the platform ask `exceptionInfo` of every thread, does not catch the error
     * an adapter answers for the threads without one, and the stop is never shown (seen with the .NET adapter). Told that one thread
     * has stopped, the platform asks that thread only. Resume and the steps continue all goroutines anyway.
     */
    fun forPlatform(args: StoppedEventArguments): StoppedEventArguments {
        if (args.reason == StoppedEventArgumentsReason.EXCEPTION && args.allThreadsStopped == true) args.allThreadsStopped = false
        return args
    }

    companion object {
        /** The one of [threads] (each with its id) with [stoppedId]; null when the event named no thread or the thread is not listed. */
        fun <T> choose(threads: List<Pair<T, Int>>, stoppedId: Int?): T? = threads.firstOrNull { it.second == stoppedId }?.first
    }
}
