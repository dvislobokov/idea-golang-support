package io.github.golangsupport.run

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.golangsupport.mod.GoModulesService
import java.io.File

/** A place in Go code named by a line of output: [start] and [end] are offsets in the line, [line] and [column] one-based. */
data class GoOutputLocation(val start: Int, val end: Int, val path: String, val line: Int, val column: Int)

/**
 * `file.go:12:5` wherever the toolchain prints it: errors of the compiler and of vet, `t.Errorf` of a test (`    order_test.go:39: ...`,
 * the bare name of the file), the frames of a panic (`\tC:/app/store/order.go:41 +0x1d`), log lines with `log.Lshortfile`.
 */
object GoOutputLocations {
    private val LOCATION = Regex("""((?:[A-Za-z]:)?[^\s:"'()\[\]]+\.go):(\d+)(?::(\d+))?""")

    fun find(line: String): List<GoOutputLocation> = LOCATION.findAll(line).map { match ->
        GoOutputLocation(match.range.first, match.range.last + 1, match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toIntOrNull() ?: 1)
    }.toList()
}

class GoConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(GoConsoleFilter(project))
}

class GoConsoleFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (".go:" !in line) return null
        val lineStart = entireLength - line.length
        val items = GoOutputLocations.find(line).mapNotNull { location ->
            val file = resolve(location.path) ?: return@mapNotNull null
            Filter.ResultItem(lineStart + location.start, lineStart + location.end, OpenFileHyperlinkInfo(project, file, location.line - 1, location.column - 1))
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    /** An absolute path; a path from the directory of a module or of the project; a bare file name, when the project has exactly one such file. */
    private fun resolve(path: String): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()
        if (File(path).isAbsolute) return fileSystem.findFileByPath(path.replace('\\', '/'))
        return ReadAction.compute<VirtualFile?, RuntimeException> {
            if (project.isDisposed) return@compute null
            val roots = GoModulesService.getInstance(project).commandDirectories() + listOfNotNull(project.guessProjectDir())
            roots.firstNotNullOfOrNull { it.findFileByRelativePath(path.replace('\\', '/').removePrefix("./")) }
                ?: if ('/' in path || '\\' in path || DumbService.isDumb(project)) null
                else FilenameIndex.getVirtualFilesByName(path, GlobalSearchScope.projectScope(project)).singleOrNull()
        }
    }
}
