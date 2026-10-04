package io.github.golangsupport.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.GoTestNames
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.mod.GoModulesService

/** The packages of the project offered by the Package list of a configuration: programs for `go run`, packages with tests for `go test`. */
object GoPackageChoices {
    /** [directory]: the path stored in the configuration; [label]: the import path, or the path in the project outside a module. */
    class Choice(val directory: String, val label: String, val program: Boolean, val tests: Boolean)

    /** Every directory with Go files under the modules of the project, sorted by [Choice.label]. Read action; stubs only. */
    fun collect(project: Project): List<Choice> {
        val modules = GoModulesService.getInstance(project)
        val found = LinkedHashMap<VirtualFile, Pair<Boolean, Boolean>>()
        for (root in modules.commandDirectories()) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>(limit(MAX_DEPTH)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file == root || file.name !in SKIPPED && !file.name.startsWith(".") && !file.name.startsWith("_")
                    val directory = file.parent ?: return true
                    if (file.extension != "go" || found.size >= MAX_PACKAGES && directory !in found) return true
                    val (program, tests) = found[directory] ?: (false to false)
                    val test = file.name.endsWith(GoTestNames.TEST_SUFFIX)
                    val main = !test && !program && (PsiManager.getInstance(project).findFile(file) as? GoFile)?.let(GoRunConfigurationGenerator::isProgram) == true
                    found[directory] = (program || main) to (tests || test)
                    return true
                }
            })
        }
        return found.map { (directory, kind) -> Choice(directory.path, label(project, directory), kind.first, kind.second) }.sortedBy { it.label }
    }

    private fun label(project: Project, directory: VirtualFile): String =
        GoModulesService.getInstance(project).moduleOf(directory)?.importPath(directory)
            ?: project.basePath?.let { base -> directory.path.removePrefix(base).trimStart('/').ifEmpty { "." } } ?: directory.path

    /** The choices for [command]; [current] stays in the list whatever it is (a file, a directory chosen with Browse). */
    fun forCommand(choices: List<Choice>, command: GoCommand, current: String?): List<Choice> {
        val fit = choices.filter { if (command == GoCommand.TEST) it.tests else it.program }
        val path = current?.trim()?.takeIf { it.isNotEmpty() } ?: return fit
        if (fit.any { sameDirectory(it.directory, path) }) return fit
        return listOf(choices.firstOrNull { sameDirectory(it.directory, path) } ?: Choice(path, path, program = false, tests = false)) + fit
    }

    fun sameDirectory(a: String, b: String): Boolean = a.replace('\\', '/').trimEnd('/').equals(b.replace('\\', '/').trimEnd('/'), ignoreCase = true)

    private const val MAX_DEPTH = 12
    private const val MAX_PACKAGES = 2000
    private val SKIPPED = setOf("vendor", "testdata", "node_modules")
}
