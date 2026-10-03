package io.github.golangsupport.run

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.execution.RunManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.GoTestNames
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings

/**
 * Creates run configurations for the programs of the project, so that a project that was just opened has something in the list:
 * one per directory with a `package main` that has a `func main` (`cmd/api`, `cmd/worker`, the root of a small module).
 */
@Service(Service.Level.PROJECT)
class GoRunConfigurationGenerator(private val project: Project) {
    data class Target(val name: String, val directory: String)

    /** Collects targets in the background and registers the missing configurations on EDT. */
    fun schedule() {
        if (!GoSettings.getInstance().createRunConfigurations) return
        // a directory made while the IDE was closed is not in the VFS yet (seen live: a new cmd/x got no configuration until a refresh); the
        // refresh is asked for once the indexes are there, and the scan waits for its events to have been applied. The modules are looked
        // for in the background: `runWhenSmart` runs its code on EDT, where asking the index is a slow operation (SEVERE, seen live)
        ReadAction.nonBlocking<List<VirtualFile>> { GoModulesService.getInstance(project).commandDirectories() }
            .inSmartMode(project).expireWhen { project.isDisposed }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { roots ->
                RefreshQueue.getInstance().refresh(true, true, {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (project.isDisposed) return@executeOnPooledThread
                        val targets = ReadAction.computeBlocking<List<Target>, RuntimeException> { if (project.isDisposed) emptyList() else collectTargets() }
                        if (targets.isNotEmpty()) ApplicationManager.getApplication().invokeLater({ register(targets) }, project.disposed)
                    }
                }, *roots.toTypedArray())
            }
    }

    fun collectTargets(): List<Target> {
        val programs = LinkedHashSet<VirtualFile>()
        for (root in GoModulesService.getInstance(project).commandDirectories()) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>(limit(MAX_DEPTH)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file.name !in SKIPPED_DIRECTORIES && !file.name.startsWith(".") && !file.name.startsWith("_")
                    val directory = file.parent ?: return true
                    if (file.extension != "go" || file.name.endsWith(GoTestNames.TEST_SUFFIX) || directory in programs || programs.size >= MAX_TARGETS) return true
                    val psi = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return true
                    if (isProgram(psi)) programs += directory
                    return true
                }
            })
        }
        return programs.map { Target(name(it), it.path) }
    }

    /** `go run api` for `cmd/api`; the program in the root of a module is named after the module. */
    private fun name(directory: VirtualFile): String {
        val module = GoModulesService.getInstance(project).moduleOf(directory)
        return "go run " + if (module != null && module.root == directory) module.path.substringAfterLast('/') else directory.name
    }

    fun register(targets: List<Target>) {
        val runManager = RunManager.getInstance(project)
        val properties = PropertiesComponent.getInstance(project)
        // Every target is generated once: a configuration the user has deleted or reworked does not come back.
        val generated = properties.getList(GENERATED_KEY).orEmpty().toMutableSet()
        val existing = runManager.allConfigurationsList.filterIsInstance<GoRunConfiguration>().filter { it.options.command == GoCommand.RUN }.mapTo(HashSet()) { it.options.target.orEmpty() }

        for (target in targets) {
            if (!generated.add(target.directory) || target.directory in existing) continue
            val settings = runManager.createConfiguration(target.name, GoConfigurationType.instance.factory)
            (settings.configuration as GoRunConfiguration).options.apply {
                command = GoCommand.RUN
                this.target = target.directory
            }
            settings.storeInLocalWorkspace()
            runManager.addConfiguration(settings)
            if (runManager.selectedConfiguration == null) runManager.selectedConfiguration = settings
        }
        properties.setList(GENERATED_KEY, generated.toList())
    }

    companion object {
        private const val GENERATED_KEY = "golang.generated.run.configurations"
        private const val MAX_DEPTH = 6
        private const val MAX_TARGETS = 30
        private val SKIPPED_DIRECTORIES = setOf("vendor", "testdata", "node_modules", "examples", "third_party")

        /** A file of a program: `package main` with a `func main`, from its stubs. Read action. */
        fun isProgram(file: GoFile): Boolean = file.packageName == "main" && file.functions.any { it.name == "main" }

        fun getInstance(project: Project): GoRunConfigurationGenerator = project.service()
    }
}

class GoRunConfigurationStartupActivity : ProjectActivity {
    // without Go files there is nothing to generate, and the scan would refresh the whole project directory
    override suspend fun execute(project: Project) {
        if (GoProjectPresence.hasGoFiles(project)) GoRunConfigurationGenerator.getInstance(project).schedule()
    }
}
