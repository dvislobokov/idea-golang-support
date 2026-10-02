package io.github.golangsupport.project.impl

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import org.jetbrains.annotations.ApiStatus

/** What [GoRootsProvider] exposes as synthetic libraries: nothing, `$GOROOT/src`, or also the module directories of the build list. */
@ApiStatus.Internal
enum class GoLibraryRootsMode { NONE, STANDARD_LIBRARY, STANDARD_LIBRARY_AND_DEPENDENCIES }

/**
 * Application service that decides the library roots of a project. The default reads the registry; a host plugin overrides it with
 * a user setting and calls [GoRootsProvider.scheduleRootsUpdate] for the open projects when the setting changes.
 */
@ApiStatus.Internal
interface GoLibraryRootsPolicy {
    fun modeFor(project: Project): GoLibraryRootsMode

    companion object {
        @JvmStatic
        fun getInstance(): GoLibraryRootsPolicy = ApplicationManager.getApplication().getService(GoLibraryRootsPolicy::class.java)
    }
}

/** The registry key `gopsi.libraryRoots` (default true): off is [GoLibraryRootsMode.NONE], on is everything. */
@ApiStatus.Internal
class DefaultGoLibraryRootsPolicy : GoLibraryRootsPolicy {
    override fun modeFor(project: Project): GoLibraryRootsMode =
        if (runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true)) GoLibraryRootsMode.STANDARD_LIBRARY_AND_DEPENDENCIES else GoLibraryRootsMode.NONE

    companion object {
        const val REGISTRY_KEY = "gopsi.libraryRoots"
    }
}
