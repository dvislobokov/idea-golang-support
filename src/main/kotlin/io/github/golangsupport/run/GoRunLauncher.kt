package io.github.golangsupport.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project

/** Starts a temporary run configuration of the plugin from code: the test tool window, the code lenses of gopls. EDT. */
object GoRunLauncher {
    fun runTests(project: Project, directory: String, name: String, pattern: String?, benchmark: Boolean, debug: Boolean = false, recursive: Boolean = false, fuzz: Boolean = false, coverage: Boolean = false) {
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(name, GoConfigurationType.instance.factory)
        (settings.configuration as GoRunConfiguration).options.apply {
            command = GoCommand.TEST
            target = directory
            testPattern = pattern
            this.benchmark = benchmark
            this.recursive = recursive
            this.fuzz = fuzz
            this.coverage = coverage
        }
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance())
    }
}
