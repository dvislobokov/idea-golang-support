package io.github.golangsupport.debugger

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.xdebugger.SplitDebuggerMode
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import com.intellij.xdebugger.attach.XAttachPresentationGroup
import com.intellij.xdebugger.attach.XAttachProcessPresentationGroup
import io.github.golangsupport.GoIcons
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.run.DlvDap
import io.github.golangsupport.run.GoLaunchArguments
import io.github.golangsupport.run.GoRunConfiguration
import io.github.golangsupport.settings.GoSettings
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import javax.swing.Icon

private val LOG = logger<GoDebugRunner>()

/**
 * Debug of a "Go" configuration (`go run` and `go test` alike: delve builds and starts the program or the test binary itself) and of an
 * attach to a process. The plugin's own DAP client talks to `dlv dap` over the XDebugger API, which every IDE has.
 */
class GoDebugRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "GoDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && (profile is GoAttachProfile || profile is GoRunConfiguration)

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val result = AsyncPromise<RunContentDescriptor?>()
        val project = environment.project
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val start = debugStart(environment)
                val delve = GoTool.DELVE.find() ?: run {
                    GoTool.DELVE.offerInstallation(project, "Debug")
                    throw ExecutionException("The debugger is not installed: dlv is not found. Install it with: go install ${GoTool.DELVE.module}@latest")
                }
                val directory = (environment.runProfile as? GoRunConfiguration)?.packageDirectory()
                val log = GoDebuggerLogs.newAdapterLog()
                LOG.info("Starting $delve in $directory, log: ${log ?: "off"}")
                val commandLine = GoCli.toolCommandLine(delve.path, directory, *DlvDap.arguments(log != null, GoSettings.getInstance().debugAnyGoVersion).toTypedArray())
                val adapter = DelveProcess(commandLine, log)
                ApplicationManager.getApplication().invokeLater({
                    try {
                        val session = XDebuggerManager.getInstance(project).startSession(environment, object : XDebugProcessStarter() {
                            override fun start(session: XDebugSession): XDebugProcess = GoDebugProcess(session, adapter, start, GoDebuggerLogs.newProtocolTrace())
                        })
                        // The descriptor is what registers the run with the IDE: the Stop button, the running status, the Debug tab. In the split
                        // mode of the debugger (2026.1) `runContentDescriptor` logs an error and hands out the mock descriptor of the session;
                        // that one is asked for directly. Null there left the session running without Stop and without a tab (seen live).
                        val descriptor = if (SplitDebuggerMode.isSplitDebugger()) (session as? XDebugSessionImpl)?.getMockRunContentDescriptorIfInitialized() else null
                        result.setResult(descriptor ?: session.runContentDescriptor)
                    } catch (e: Exception) {
                        adapter.stop(0)
                        result.setError(e)
                    }
                }, ModalityState.nonModal())
            } catch (e: Exception) {
                result.setError(e)
            }
        }
        return result
    }

    private fun debugStart(environment: ExecutionEnvironment): DebugStart = when (val profile = environment.runProfile) {
        is GoAttachProfile -> DebugStart(attach = true, arguments = GoLaunchArguments.attach(profile.processId), name = profile.name)
        is GoRunConfiguration -> DebugStart(attach = false, arguments = profile.debugLaunchArguments(), name = profile.name)
        else -> throw ExecutionException("${profile.name} cannot be debugged by delve")
    }
}

/** A process to attach to, as a run profile: that is what an execution environment and the runner work on. The state starts nothing. */
class GoAttachProfile(val processId: Int, private val title: String) : RunProfile {
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = RunProfileState { _, _ -> null }
    override fun getName(): String = title
    override fun getIcon(): Icon = GoIcons.File
}

/**
 * Run | Attach to Process: delve for a local process. Which processes are Go programs cannot be told from the list cheaply (`go version -m`
 * per executable would be), so every process is offered under "Go", as GoLand does; delve refuses a process without Go runtime.
 */
class GoAttachDebuggerProvider : XAttachDebuggerProvider {
    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost

    override fun getPresentationGroup(): XAttachPresentationGroup<ProcessInfo> = Group

    override fun getAvailableDebuggers(project: Project, hostInfo: XAttachHost, process: ProcessInfo, contextHolder: UserDataHolder): List<XAttachDebugger> =
        if (process.pid.toLong() == ProcessHandle.current().pid()) emptyList() else listOf(Debugger)

    private object Group : XAttachProcessPresentationGroup {
        override fun getOrder(): Int = 10
        override fun getGroupName(): String = "Go"
        override fun getItemIcon(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): Icon = GoIcons.File
        override fun getItemDisplayText(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): String = info.executableDisplayName
    }

    private object Debugger : XAttachDebugger {
        override fun getDebuggerDisplayName(): String = "Go Debugger (delve)"

        override fun attachDebugSession(project: Project, hostInfo: XAttachHost, info: ProcessInfo) {
            val profile = GoAttachProfile(info.pid, "${info.executableDisplayName} (${info.pid})")
            val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), profile).build()
            ExecutionManager.getInstance(project).restartRunProfile(environment)
        }
    }
}
