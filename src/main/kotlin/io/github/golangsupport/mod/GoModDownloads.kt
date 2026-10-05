package io.github.golangsupport.mod

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.github.golangsupport.GoBundle
import io.github.golangsupport.settings.GoSettings

/**
 * "Download Go module dependencies" of Settings | Go | Go Modules, GoLand's four choices: a machine-wide switch ([GoSettings.downloadDependencies])
 * and the current project as the exception to it, kept in the project.
 */
enum class GoModDownloadChoice(val title: String, val global: Boolean, val exception: Boolean) {
    ALL("Enable for all projects", true, false),
    NONE("Disable for all projects", false, false),
    ONLY_THIS("Enable for current project, disable for other projects", false, true),
    ALL_BUT_THIS("Disable for current project, enable for other projects", true, true);

    /** What the combo shows; [toString] stays English. */
    val label: String get() = GoBundle.messageOr("modules.download.$name", title)

    /** Whether the project the choice was made in downloads: the machine-wide switch, turned over by the exception. */
    val enabledHere: Boolean get() = global != exception

    override fun toString(): String = title

    companion object {
        fun of(global: Boolean, exception: Boolean): GoModDownloadChoice = entries.first { it.global == global && it.exception == exception }
    }
}

/** Whether `go mod download` follows a save of go.mod with other requirements ([GoModSaveListener]). */
object GoModDownloads {
    private const val EXCEPTION = "go.modules.download.exception"

    fun choice(project: Project): GoModDownloadChoice =
        GoModDownloadChoice.of(GoSettings.getInstance().downloadDependencies, PropertiesComponent.getInstance(project).getBoolean(EXCEPTION))

    fun set(project: Project, choice: GoModDownloadChoice) {
        GoSettings.getInstance().downloadDependencies = choice.global
        PropertiesComponent.getInstance(project).setValue(EXCEPTION, choice.exception)
    }

    fun isEnabled(project: Project): Boolean = choice(project).enabledHere
}
