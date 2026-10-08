package io.github.golangsupport.mod

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.ide.inspections.GoDiagnosticClasses
import io.github.golangsupport.ide.inspections.GoDiagnosticsInspectionBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.semantic.api.GoDiagnostic
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * `cannot find package "p"` on an import path (the checker's `missing-package`), as GoLand marks it. Lives in the host plugin, not in
 * go-psi-ide, because its fixes run `go`: "Sync dependencies of <module>" (`go get <path>`) and "Run go mod tidy" in the module root.
 * A file outside any module gets no fixes.
 */
class GoMissingPackageInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.MISSING_PACKAGE

    override fun fixes(d: GoDiagnostic, file: GoFile, element: PsiElement): List<LocalQuickFix> {
        val module = GoModulesService.getInstance(file.project).moduleOf(file.originalFile.virtualFile) ?: return emptyList()
        val path = importPath(d.message) ?: return emptyList()
        // the path is source text that becomes an argument of `go get`: only a well-formed import path gets the Sync fix
        return listOfNotNull(GoSyncDependenciesFix(module.path, module.root.path, path).takeIf { isSafeImportPath(path) }, GoModTidyFix(module.root.path))
    }

    companion object {
        private val MESSAGE = Regex("^cannot find package \"(.+)\"$")

        private val IMPORT_PATH_CHARS = Regex("[A-Za-z0-9._~/+-]+")

        /**
         * Whether [path] is a well-formed import path (the character rules of `golang.org/x/mod/module.CheckImportPath`): non-empty,
         * no leading `-` (it would be read as a flag of `go get`), only `[A-Za-z0-9._~/+-]`, no empty, `.` or `..` elements.
         */
        fun isSafeImportPath(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith("-") && IMPORT_PATH_CHARS.matches(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        /** The import path of a `missing-package` diagnostic. */
        fun importPath(message: String): String? = MESSAGE.find(message)?.groupValues?.get(1)

        /**
         * After `go get` / `go mod tidy`: the new module directories of the module cache are made known to the VFS (the resolver finds
         * them without a refresh), the project model is dropped, then the editors are analyzed again. Called on EDT.
         */
        internal fun reanalyze(project: Project, importPaths: List<String>) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val cache = GoToolchainProvider.getInstance().toolchainFor(project)?.gomodcache
                if (cache != null) for (path in importPaths) moduleDirectories(cache, path).forEach { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                if (project.isDisposed) return@executeOnPooledThread
                GoProjectModelTracker.getInstance(project).bump("missing package fix")
                ApplicationManager.getApplication().invokeLater({ DaemonCodeAnalyzer.getInstance(project).restart() }, project.disposed)
            }
        }

        /** `<cache>/<escaped prefix>@<version>` directories for every path prefix of [importPath] that the cache has (`go get` just added them). */
        internal fun moduleDirectories(cache: Path, importPath: String): List<Path> {
            val segments = importPath.split('/')
            val out = ArrayList<Path>()
            for (n in 1..segments.size) {
                val prefix = segments.take(n).map(::escape)
                val parent = prefix.dropLast(1).fold(cache) { p, s -> p.resolve(s) }
                if (!Files.isDirectory(parent)) break
                Files.newDirectoryStream(parent, prefix.last() + "@*").use { stream -> stream.filterTo(out) { Files.isDirectory(it) } }
            }
            return out
        }

        /** Module cache case encoding (`golang.org/x/mod/module.EscapePath`): an upper-case letter becomes `!` and the lower-case one. */
        internal fun escape(segment: String): String = buildString { for (c in segment) if (c.isUpperCase()) append('!').append(c.lowercaseChar()) else append(c) }
    }
}

/** `go get <path>` in the module root: what GoLand calls "Sync dependencies of <module>". */
class GoSyncDependenciesFix(private val moduleName: String, private val root: String, private val importPath: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Sync dependencies"
    override fun getName(): String = "Sync dependencies of $moduleName"
    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) = runGo(project, "Go Get $importPath", root, listOf("get", importPath), listOf(importPath))
}

/** `go mod tidy` in the module root: adds every missing requirement and drops the unused ones. */
class GoModTidyFix(private val root: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Run go mod tidy"
    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile as? GoFile
        runGo(project, "Go Mod Tidy", root, listOf("mod", "tidy"), file?.imports?.map { it.path }.orEmpty())
    }
}

/** A `go` command in [root] in the background (Build window); on success the model and the editors catch up with go.mod and the cache. */
private fun runGo(project: Project, title: String, root: String, arguments: List<String>, importPaths: List<String>) {
    val commands = GoCli.commandLinesOrNotify(project, title) { listOf(GoCli.commandLine(root, *arguments.toTypedArray())) } ?: return
    GoCli.runInBackground(project, title, commands, refresh = listOf(File(root)), onSuccess = { GoMissingPackageInspection.reanalyze(project, importPaths) })
}
