package io.github.golangsupport.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.ml.GoMlModels

/** Routes the debug lines of the network (setting "Log every answer of the network") into the plugin log, category `ml`. */
class GoMlLogBridge : ProjectActivity {
    override suspend fun execute(project: Project) {
        GoMlModels.debugSink = { GoPluginLog.info("ml", it) }
    }
}
