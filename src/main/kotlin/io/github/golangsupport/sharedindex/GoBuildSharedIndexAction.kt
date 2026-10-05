package io.github.golangsupport.sharedindex

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.settings.GoSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Go | Build Shared Index for GOROOT...: runs `dump-shared-index project` of this IDE in a separate headless process over a throwaway
 * project whose only library is the project's GOROOT, and puts the chunk where [GoSharedIndexFinder] looks. The next open of any project
 * with that Go release attaches it instead of indexing `$GOROOT/src` (docs/SHARED-INDEXES.md).
 */
class GoBuildSharedIndexAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread { prepareAndRun(project) }
    }

    private fun prepareAndRun(project: Project) {
        val log = GoSharedIndexes.LOG_CATEGORY
        val (goroot, key) = GoSharedIndexes.gorootOf(project) ?: run {
            GoCli.notifyError(project, TITLE, "No released Go toolchain for this project: GOROOT without a goX.Y VERSION file (a development tree) or no Go at all.")
            return
        }
        val scriptName = ApplicationNamesInfo.getInstance().scriptName
        val os = when {
            SystemInfo.isWindows -> GoSharedIndexCommand.Os.WINDOWS
            SystemInfo.isMac -> GoSharedIndexCommand.Os.MAC
            else -> GoSharedIndexCommand.Os.LINUX
        }
        val home = Path.of(PathManager.getHomePath())
        val launcher = GoSharedIndexCommand.launcherCandidates(home, scriptName, os).firstOrNull { it.isRegularFile() } ?: run {
            GoCli.notifyError(project, TITLE, "No launcher of this IDE under $home (looked for $scriptName in bin/ and MacOS/).")
            return
        }
        val work = GoSharedIndexes.root().resolve(".work").resolve(key.id)
        val command = try {
            if (work.exists()) NioFiles.deleteRecursively(work)
            val projectDir = Files.createDirectories(work.resolve("project"))
            projectDir.resolve("go.mod").writeText(GoSharedIndexCommand.goMod(key))
            GoSharedIndexCommand.settingsXml(GoSettings.getInstance().goPath)?.let { xml ->
                Files.createDirectories(work.resolve("config").resolve("options")).resolve("golang-support.xml").writeText(xml)
            }
            val properties = work.resolve("idea.properties").also { it.writeText(GoSharedIndexCommand.properties(work, Path.of(PathManager.getPluginsPath()))) }
            val output = Files.createDirectories(work.resolve("out"))
            val temp = Files.createDirectories(work.resolve("tmp"))
            GeneralCommandLine(listOf(launcher.toString()) + GoSharedIndexCommand.arguments(projectDir, output, temp, key))
                .withWorkingDirectory(projectDir)
                .withEnvironment(GoSharedIndexCommand.propertiesVariables(scriptName).associateWith { properties.toString() })
        } catch (e: Exception) {
            GoCli.notifyError(project, TITLE, "Could not prepare $work: ${GoPluginLog.describe(e)}")
            return
        }
        GoPluginLog.info(log, "Building GOROOT shared index ${key.id} for $goroot: ${GoCli.displayString(command)}")
        GoCli.runInBackground(project, TITLE, listOf(command), onSuccess = {
            ApplicationManager.getApplication().executeOnPooledThread { collect(project, key, work) }
        })
    }

    /** `bin\idea.bat` loses the exit code of the JVM, so success is "a chunk was written". */
    private fun collect(project: Project, key: GoSharedIndexKey, work: Path) {
        val log = GoSharedIndexes.LOG_CATEGORY
        val output = work.resolve("out")
        val written = Files.list(output).use { s -> s.filter { it.isRegularFile() }.toList() }
        if (written.none { it.name.endsWith(GoSharedIndexLayout.CHUNK_SUFFIX) }) {
            GoCli.notifyError(project, TITLE, "The headless IDE wrote no chunk; its log: ${work.resolve("log").resolve("idea.log")}")
            return
        }
        try {
            val target = GoSharedIndexes.directory(key)
            if (target.exists()) NioFiles.deleteRecursively(target)
            Files.createDirectories(target)
            for (file in written) Files.move(file, target.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
            val manifest = GoSharedIndexLayout.Manifest(key.id, key.goVersion, GoSharedIndexes.ideBuild(), GoSharedIndexes.pluginVersion(), LocalDateTime.now().toString())
            target.resolve(GoSharedIndexLayout.MANIFEST).writeText(GoSharedIndexLayout.manifestJson(manifest))
            NioFiles.deleteRecursively(work)
            val chunks = GoSharedIndexes.chunks(key)
            GoPluginLog.info(log, "GOROOT shared index ${key.id} written to $target: ${chunks.joinToString { "${it.name} (${Files.size(it) / 1024} KB)" }}")
            GoCli.notifyInfo(project, TITLE, "Shared index of ${key.goVersion} (${key.goos}/${key.goarch}) is in $target. Projects opened from now on attach it instead of indexing GOROOT.")
        } catch (e: Exception) {
            GoCli.notifyError(project, TITLE, "Could not move the chunk out of $output: ${GoPluginLog.describe(e)}")
        }
    }

    private companion object {
        const val TITLE = "Build Shared Index for GOROOT"
    }
}
