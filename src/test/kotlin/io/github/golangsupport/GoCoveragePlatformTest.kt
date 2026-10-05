package io.github.golangsupport

import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.DefaultCoverageFileProvider
import com.intellij.coverage.CoverageExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.coverage.GoCoverageEngine
import io.github.golangsupport.coverage.GoCoverageProgramRunner
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoConfigurationType
import io.github.golangsupport.run.GoProfilerExecutor
import io.github.golangsupport.run.GoProfilerRunner
import io.github.golangsupport.run.GoRunConfiguration
import io.github.golangsupport.coverage.GoCoverageRunner
import io.github.golangsupport.coverage.GoCoverageSuite
import org.jdom.Element
import java.io.File

/** The coverage engine of the platform for Go: registered by go-coverage.xml, the files it counts, its suites surviving a restart. */
class GoCoveragePlatformTest : BasePlatformTestCase() {
    fun testEngineAndRunnerAreRegistered() {
        assertTrue(CoverageEngine.EP_NAME.extensionList.any { it is GoCoverageEngine })
        assertTrue(CoverageRunner.getInstanceById(GoCoverageRunner.ID) is GoCoverageRunner)
        assertTrue(GoCoverageRunner.getInstance().acceptsCoverageEngine(GoCoverageEngine.getInstance()))
    }

    fun testExecutorsRunnersAndRunMenu() {
        val configuration = GoConfigurationType.instance.factory.createTemplateConfiguration(project) as GoRunConfiguration
        configuration.options.command = GoCommand.TEST
        configuration.options.target = myFixture.tempDirPath
        assertTrue(ProgramRunner.getRunner(CoverageExecutor.EXECUTOR_ID, configuration) is GoCoverageProgramRunner)
        assertTrue(ProgramRunner.getRunner(GoProfilerExecutor.EXECUTOR_ID, configuration) is GoProfilerRunner)
        assertTrue(GoCoverageEngine.getInstance().isApplicableTo(configuration))
        configuration.options.command = GoCommand.RUN
        assertNull(ProgramRunner.getRunner(CoverageExecutor.EXECUTOR_ID, configuration))
        assertNull(ProgramRunner.getRunner(GoProfilerExecutor.EXECUTOR_ID, configuration))
        assertNotNull(GoProfilerExecutor.getInstance())
        val actions = ActionManager.getInstance()
        fun ids(group: String) = (actions.getAction(group) as ActionGroup).getChildren(null).mapNotNull { actions.getId(it) }
        assertTrue(ids("RunnerActions").toString(), "Go.RunWithProfiler" in ids("RunnerActions"))
        assertTrue("Go.DumpGoroutines" in ids("RunMenu"))
    }

    fun testFilesTheEngineCounts() {
        val engine = GoCoverageEngine.getInstance()
        val source = myFixture.addFileToProject("store/order.go", "package store\n")
        val test = myFixture.addFileToProject("store/order_test.go", "package store\n")
        val text = myFixture.addFileToProject("store/notes.txt", "notes\n")
        val bundle = CoverageSuitesBundle(suite("tests"))
        assertTrue(engine.acceptedByFilters(source, bundle))
        assertFalse(engine.acceptedByFilters(test, bundle))
        assertFalse(engine.acceptedByFilters(text, bundle))
        assertTrue(engine.coverageEditorHighlightingApplicableTo(source))
        assertTrue(engine.coverageProjectViewStatisticsApplicableTo(source.virtualFile))
        assertFalse(engine.coverageProjectViewStatisticsApplicableTo(test.virtualFile))
        assertFalse(engine.coverageProjectViewStatisticsApplicableTo(source.virtualFile.parent))
        // the "class" of a file is its path, the key GoCoverageProjectData gives it
        assertEquals(setOf(source.virtualFile.path), engine.getQualifiedNames(source))
    }

    fun testSuiteSurvivesSerialization() {
        val suite = suite("store tests")
        val element = Element("suite")
        suite.writeExternal(element)
        val restored = GoCoverageEngine.getInstance().createEmptyCoverageSuite(GoCoverageRunner.getInstance()) as GoCoverageSuite
        restored.readExternal(element)
        restored.setProject(project)
        assertEquals("store tests", restored.presentableName)
        // the platform keeps the path relative to its system directory: the same file, not the same string
        assertEquals(File(suite.coverageDataFileName).canonicalPath, File(restored.coverageDataFileName).canonicalPath)
        assertEquals(1_700_000_000_000L, restored.lastCoverageTimeStamp)
        assertTrue(restored.runner is GoCoverageRunner)
        assertTrue(restored.coverageEngine is GoCoverageEngine)
    }

    private val coverFile: File by lazy { com.intellij.openapi.util.io.FileUtil.createTempFile("go-cover-", ".out", true) }

    private fun suite(name: String): GoCoverageSuite =
        GoCoverageSuite(name, project, GoCoverageRunner.getInstance(), DefaultCoverageFileProvider(coverFile), 1_700_000_000_000L)
}
