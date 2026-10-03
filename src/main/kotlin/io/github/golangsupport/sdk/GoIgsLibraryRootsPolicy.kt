package io.github.golangsupport.sdk

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.golangsupport.project.impl.GoLibraryRootsMode
import io.github.golangsupport.project.impl.GoLibraryRootsPolicy
import io.github.golangsupport.project.impl.GoRootsProvider
import io.github.golangsupport.settings.GoSettings

/**
 * The library roots of the native PSI follow Settings | Go | Go Modules, "Index for navigation" ([GoSettings.libraryRoots]). Replaces
 * `DefaultGoLibraryRootsPolicy` of go-psi, which reads a registry key (the service is overridden in plugin.xml).
 */
class GoIgsLibraryRootsPolicy : GoLibraryRootsPolicy {
    override fun modeFor(project: Project): GoLibraryRootsMode = GoSettings.getInstance().libraryRoots.mode

    companion object {
        /** After the setting changed: every open project tells the platform what its roots are now, so it re-indexes without a restart. */
        fun settingChanged() {
            for (project in ProjectManager.getInstance().openProjects) if (!project.isDisposed) GoRootsProvider.scheduleRootsUpdate(project)
        }
    }
}
