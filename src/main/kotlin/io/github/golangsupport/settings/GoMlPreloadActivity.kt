package io.github.golangsupport.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.startup.ProjectActivity
import io.github.golangsupport.lang.GoProjectPresence
import io.github.golangsupport.ml.GoMlModels

/**
 * Loads and warms up the models (the network's JIT and native kernels, the ranker) when a project with Go files is open and indexed, so the
 * first grey text does not pay the second of loading. A project without Go files pays nothing (the ~100 MB of the network stay unloaded);
 * nothing is loaded at the start of the application. The presence of Go files is answered by the file type index, in smart mode.
 */
class GoMlPreloadActivity internal constructor(private val preload: () -> Unit) : ProjectActivity {
    @Suppress("unused") constructor() : this({ GoMlModels.getInstance().preload() })

    override suspend fun execute(project: Project) {
        if (hasGoFiles(project)) preload()
    }

    /** Waits for smart mode, then asks the index (through the presence service, which also notes the answer for the menu Go). */
    suspend fun hasGoFiles(project: Project): Boolean = smartReadAction(project) { !project.isDisposed && GoProjectPresence.getInstance(project).recompute() }
}
