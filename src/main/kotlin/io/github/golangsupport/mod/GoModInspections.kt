package io.github.golangsupport.mod

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.GlobalSearchScopes
import com.intellij.util.indexing.FileBasedIndex
import io.github.golangsupport.lang.index.GoFileImportsIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.awt.datatransfer.StringSelection

/** Thin wrapper: [GoModChecks] does the work over the text of the file, this one answers its questions from the VFS and turns problems into descriptors. */
abstract class GoModInspectionBase(private val group: String) : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is GoModPsiFile) return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val dir = file.virtualFile?.parent ?: file.originalFile.virtualFile?.parent
        val isWork = file.name == GoModFileType.GO_WORK
        val env = GoModEnvironment({ state(dir, it) }, vendorText(dir), imports(file, dir, document))
        val problems = GoModChecks.check(document.immutableCharSequence, isWork, env).filter { it.rule.group == group && it.line < document.lineCount }
        return problems.map { p ->
            val fixes = when (p.rule) {
                GoModRule.DUPLICATE_REQUIRE -> arrayOf<LocalQuickFix>(RemoveDuplicateRequireFix(p.line, p.duplicateOf!!))
                GoModRule.VENDOR_SYNC -> arrayOf<LocalQuickFix>(CopyVendorCommandFix)
                GoModRule.UNUSED_REQUIRE -> arrayOf<LocalQuickFix>(RemoveUnusedRequireFix(p.line, p.needle.orEmpty()))
                else -> LocalQuickFix.EMPTY_ARRAY
            }
            manager.createProblemDescriptor(file, range(document, p), p.message, if (p.error) ProblemHighlightType.GENERIC_ERROR else ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, *fixes)
        }.toTypedArray()
    }

    /**
     * Import paths of the .go files of this module that can serve one of its requires, from the platform index of imports (no `go`, no
     * build tags: every file counts, except `vendor`, `testdata` and nested modules). Null while indexing and for the other groups.
     */
    private fun imports(file: PsiFile, dir: VirtualFile?, document: Document): Set<String>? {
        if (group != "GoModUnused" || dir == null || DumbService.isDumb(file.project)) return null
        val required = GoModFile.parse(document.immutableCharSequence).requires.map { it.path }.toSet()
        if (required.isEmpty()) return null
        val project = file.project
        val scope = GlobalSearchScopes.directoriesScope(project, true, dir)
        fun inModule(f: VirtualFile): Boolean {
            var d = f.parent
            while (d != null && d != dir) {
                if (d.name == "vendor" || d.name == "testdata" || d.findChild(GoModFileType.GO_MOD) != null) return false
                d = d.parent
            }
            return d == dir
        }
        return runCatching {
            FileBasedIndex.getInstance().getAllKeys(GoFileImportsIndex.NAME, project).filter { key -> generateSequence(key) { it.substringBeforeLast('/', "").ifEmpty { null } }.any { it in required } }
                .filter { key -> GoFileImportsIndex.filesImporting(key, project, scope).any(::inModule) }.toSet()
        }.getOrNull()
    }

    private fun range(document: Document, p: GoModProblem): TextRange {
        val start = document.getLineStartOffset(p.line)
        val text = document.getText(TextRange(start, document.getLineEndOffset(p.line)))
        val at = p.needle?.let { text.indexOf(it) } ?: -1
        if (at >= 0) return TextRange(start + at, start + at + p.needle!!.length)
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) TextRange(start, start) else TextRange(start + text.indexOf(trimmed), start + text.indexOf(trimmed) + trimmed.length)
    }

    private fun state(dir: VirtualFile?, path: String): GoDirState {
        val normal = path.replace(Char(92), '/')
        val target = (if (normal.startsWith("/") || Regex("""[A-Za-z]:/.*""").matches(normal)) LocalFileSystem.getInstance().findFileByPath(normal) else dir?.let { VfsUtilCore.findRelativeFile(normal, it) })
        return when {
            target == null || !target.isDirectory -> GoDirState.MISSING
            target.findChild(GoModFileType.GO_MOD)?.isDirectory == false -> GoDirState.OK
            else -> GoDirState.NO_GO_MOD
        }
    }

    private fun vendorText(dir: VirtualFile?): String? =
        dir?.findFileByRelativePath("vendor/modules.txt")?.takeIf { !it.isDirectory }?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }
}

/** `replace` to a missing directory, `use` of a directory without go.mod. */
class GoModPathsInspection : GoModInspectionBase("GoModPaths")

/** Duplicate requires, a module that requires itself, vendor/modules.txt out of step with go.mod. */
class GoModRequiresInspection : GoModInspectionBase("GoModRequires")

/** A malformed `go` version, a `toolchain` older than `go`. */
class GoModVersionsInspection : GoModInspectionBase("GoModVersions")

/** Deletes the line of the repeated require; [path] guards against a document that changed between the check and the fix. */
class RemoveDuplicateRequireFix(private val line: Int, private val path: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Remove duplicate require"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        if (line >= document.lineCount) return
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        if (path !in document.getText(TextRange(start, end))) return
        document.deleteString(start, minOf(document.textLength, end + 1)) // the line break too, or an empty line stays
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/** Runs nothing (the plugin does not call `go` from an inspection): puts the command on the clipboard. */
object CopyVendorCommandFix : LocalQuickFix {
    override fun getFamilyName(): String = "Copy 'go mod vendor' to the clipboard"
    override fun startInWriteAction(): Boolean = false
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) = CopyPasteManager.getInstance().setContents(StringSelection("go mod vendor"))
}

/** Deletes the line of an unused require, and the `require ( )` block when that was its last line. [path] guards against a document that changed since the check. */
class RemoveUnusedRequireFix(private val line: Int, private val path: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unused require"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val range = removalRange(document.immutableCharSequence, line, path) ?: return
        document.deleteString(range.first, range.last)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    companion object {
        /** Offsets [start, end) to delete: the line with its break, or the whole block when it holds nothing else; null when the line is not the require of [path]. */
        fun removalRange(text: CharSequence, line: Int, path: String): IntRange? {
            val lines = text.split('\n')
            if (line !in lines.indices || path !in lines[line]) return null
            fun offset(l: Int) = lines.take(l).sumOf { it.length + 1 }
            fun end(l: Int) = minOf(text.length, offset(l + 1))
            val before = (line - 1 downTo 0).firstOrNull { lines[it].isNotBlank() }
            val after = (line + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
            if (before != null && after != null && lines[before].trim().let { it.startsWith("require") && it.endsWith("(") } && lines[after].trim() == ")") return offset(before)..end(after)
            return offset(line)..end(line)
        }
    }
}

/** A direct require that no .go file of the module imports a package of. */
class GoModUnusedInspection : GoModInspectionBase("GoModUnused")
