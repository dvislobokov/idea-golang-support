package io.github.golangsupport.ide.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.semantic.cache.GoTrackers
import java.util.concurrent.ConcurrentHashMap

/**
 * How many files of the project import a path ([GoFileImportsIndex]): the packages of one name the project already uses go first in
 * completion (`rand.Int`: math/rand of 31 files of prometheus above crypto/rand of 4, which the alphabetical order put first). Cached until
 * the next out-of-block change of a project package (imports are out of block).
 */
@Service(Service.Level.PROJECT)
class GoImportUses(private val project: Project) {
    private val counts = ConcurrentHashMap<String, Int>()
    @Volatile private var stamp = -1L

    fun count(path: String): Int {
        val now = GoTrackers.getInstance(project).projectOutOfBlock.modificationCount
        if (now != stamp) { counts.clear(); stamp = now }
        return counts.computeIfAbsent(path) { GoFileImportsIndex.filesImporting(it, project, GlobalSearchScope.projectScope(project)).size }
    }

    companion object {
        fun getInstance(project: Project): GoImportUses = project.service()
    }
}
