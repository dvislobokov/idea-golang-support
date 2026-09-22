package io.github.golangsupport.testing

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.autotest.ToggleAutoTestAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.monitor.GoProfile
import io.github.golangsupport.monitor.GoProfileServers
import io.github.golangsupport.monitor.GoProfiles
import io.github.golangsupport.run.GoRunConfiguration
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor

const val TEST_FRAMEWORK_NAME = "GoTest"
private const val LOCATION_PROTOCOL = "gotest"

/** `go test -json` with the test tree instead of a plain console; the tree grows while the tests run. */
class GoTestRunState(private val configuration: GoRunConfiguration, environment: ExecutionEnvironment) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
        val profile = configuration.options.profile
        val directory = if (profile == GoProfile.NONE) null else GoProfiles.newDirectory()
        val handler = KillableColoredProcessHandler(configuration.buildCommandLine(directory)).also { ProcessTerminatedListener.attach(it) }
        if (directory != null) handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = GoProfileServers.getInstance(environment.project).notifyReady(profile, directory)
        })
        return handler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val properties = GoTestConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(TEST_FRAMEWORK_NAME, handler, properties)
        val rerunFailed = properties.createRerunFailedTestsAction(console)
        rerunFailed.setModelProvider { (console as SMTRunnerConsoleView).resultsViewer }
        return DefaultExecutionResult(console, handler, *createActions(console, handler, executor)).apply { setRestartActions(rerunFailed, ToggleAutoTestAction()) }
    }
}

class GoTestConsoleProperties(private val configuration: GoRunConfiguration, executor: Executor) :
    SMTRunnerConsoleProperties(configuration, TEST_FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    init {
        isIdBasedTestTree = true
        // The platform hides passed tests until "Show Passed" is pressed: a green run would look like an empty tree.
        setIfUndefined(TestConsoleProperties.HIDE_PASSED_TESTS, false)
        setIfUndefined(TestConsoleProperties.HIDE_IGNORED_TEST, false)
    }

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter {
        val directory = LocalFileSystem.getInstance().findFileByPath(configuration.packageDirectory().replace('\\', '/'))
        val module = GoModulesService.getInstance(configuration.project).moduleOf(directory)
        return GoTestEventsConverter(testFrameworkName, consoleProperties) { packagePath, test ->
            // example.com/app/store -> <module root>/store; a package outside the module is looked for in the directory of the run
            val packageDirectory = module?.takeIf { packagePath == it.path || packagePath.startsWith(it.path + "/") }
                ?.let { it.root.path + packagePath.removePrefix(it.path) } ?: configuration.packageDirectory()
            locationHint(packageDirectory, test)
        }
    }

    override fun getTestLocator(): SMTestLocator = GoTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction = RerunFailedGoTestsAction(consoleView as ComponentContainer, this, configuration)

    companion object {
        fun locationHint(packageDirectory: String, test: String?): String = "$LOCATION_PROTOCOL://${packageDirectory.replace('\\', '/')}|${test.orEmpty()}"
    }
}

class GoTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties, locationHint: (String, String?) -> String?) :
    OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {
    private val events = GoTestEvents(locationHint)

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
        val messages = events.convert(text) ?: return super.processServiceMessages(text, outputType, visitor)
        for (message in messages) super.processServiceMessages(message, outputType, visitor)
        return true
    }
}

/** Double click on a test: the function is looked up in the `_test.go` files of the package; a subtest leads to its function. */
object GoTestLocator : SMTestLocator {
    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != LOCATION_PROTOCOL) return emptyList()
        val (directoryPath, test) = path.split('|').takeIf { it.size == 2 } ?: return emptyList()
        val directory = LocalFileSystem.getInstance().findFileByPath(directoryPath) ?: return emptyList()
        val psiManager = PsiManager.getInstance(project)
        if (test.isEmpty()) return listOfNotNull(psiManager.findDirectory(directory)?.let { PsiLocation(it) })
        val function = test.substringBefore('/')
        for (file in directory.children.filter { it.name.endsWith(GoFile.TEST_SUFFIX) }) {
            val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
            val declaration = GoDeclarations.scan(text).declarations.firstOrNull { it.name == function && it.receiver == null } ?: continue
            val element = psiManager.findFile(file)?.findElementAt(declaration.nameRange.startOffset) ?: continue
            return listOf(PsiLocation(element))
        }
        return emptyList()
    }
}

/** Runs only the tests that failed, with a `-run` pattern of their names. */
class RerunFailedGoTestsAction(container: ComponentContainer, properties: GoTestConsoleProperties, private val configuration: GoRunConfiguration) :
    AbstractRerunFailedTestsAction(container) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile = object : MyRunProfile(configuration) {
        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
            val rerun = configuration.clone() as GoRunConfiguration
            rerun.options.testPattern = GoTests.pattern(failedTestNames(getFailedTests(configuration.project)))
            return rerun.getState(executor, environment)
        }
    }

    companion object {
        fun failedTestNames(failed: List<AbstractTestProxy>): List<String> =
            failed.mapNotNull { it.locationUrl?.substringAfter("://", "")?.substringAfter('|', "")?.takeIf(String::isNotEmpty) }.distinct()
    }
}
