package io.github.golangsupport.mod

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** The go.mod (not go.work) of [file] with its document and directory, or null. */
private class GoModLayoutContext(val file: PsiFile, val document: Document, val dir: VirtualFile?) {
    val text: CharSequence get() = document.immutableCharSequence

    /** The word [needle] of zero-based [line], or the line without its indent. */
    fun range(line: Int, needle: String?): TextRange {
        val start = document.getLineStartOffset(line)
        val text = document.getText(TextRange(start, document.getLineEndOffset(line)))
        val at = needle?.let { text.indexOf(it) } ?: -1
        if (at >= 0) return TextRange(start + at, start + at + needle!!.length)
        val indent = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return TextRange(start + indent, start + text.trimEnd().length.coerceAtLeast(indent))
    }

    companion object {
        fun of(file: PsiFile): GoModLayoutContext? {
            if (file !is GoModPsiFile || file.name != GoModFileType.GO_MOD) return null
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
            return GoModLayoutContext(file, document, file.virtualFile?.parent ?: file.originalFile.virtualFile?.parent)
        }

        /** A path of a directive, from [dir] or absolute. */
        fun resolve(dir: VirtualFile?, path: String): VirtualFile? {
            val normal = path.replace('\\', '/')
            return if (normal.startsWith("/") || Regex("""[A-Za-z]:/.*""").matches(normal)) LocalFileSystem.getInstance().findFileByPath(normal)
            else dir?.let { VfsUtilCore.findRelativeFile(normal, it) }
        }

        fun hasGoMod(dir: VirtualFile?, path: String): Boolean = resolve(dir, path)?.takeIf { it.isDirectory }?.findChild(GoModFileType.GO_MOD)?.isDirectory == false

        /** Puts [new] lines into the document of the fix's file. */
        fun apply(project: Project, descriptor: ProblemDescriptor, edit: (List<String>) -> List<String>?) {
            val file = descriptor.psiElement?.containingFile ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val new = edit(document.immutableCharSequence.split('\n')) ?: return
            GoModDirectiveIntention.replaceLines(project, document, new)
        }
    }
}

/**
 * `VgoRequireDirectivesMerge`: several `require` directives that `go mod tidy` would lay out as one block of direct requires and one of
 * `// indirect` ones. Quiet on that layout ([GoModDirectiveEdits.requiresAreGrouped]); the fix is [GoModDirectiveEdits.mergeRequires].
 */
class GoModRequireDirectivesMergeInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val context = GoModLayoutContext.of(file) ?: return null
        val lines = context.text.split('\n')
        if (GoModDirectiveEdits.requiresAreGrouped(lines)) return null
        val document = context.document
        return GoModDirectiveEdits.directives(lines).filter { it.verb == "require" && it.end < document.lineCount }.map { d ->
            // the whole directive: Alt+Enter on any of its requires offers the merge
            val range = TextRange(document.getLineStartOffset(d.start), document.getLineEndOffset(d.end))
            manager.createProblemDescriptor(file, range, MESSAGE, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, MergeRequiresFix())
        }.toTypedArray()
    }

    class MergeRequiresFix : LocalQuickFix {
        override fun getFamilyName(): String = "Merge 'require' directives"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) = GoModLayoutContext.apply(project, descriptor, GoModDirectiveEdits::mergeRequires)
    }

    companion object {
        const val MESSAGE = "Multiple 'require' directives can be merged in groups by dependency type"
    }
}

/**
 * `VgoMigrateFromReplacesToWorkspace`: a `replace` to a local module directory in a module without a go.work above it. A workspace says the
 * same without editing go.mod for a local checkout; the fix writes go.work next to go.mod and drops those replaces.
 */
class GoModMigrateToWorkspaceInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val context = GoModLayoutContext.of(file) ?: return null
        if (hasGoWorkAbove(context.dir)) return null
        val replaces = GoModLayout.workspaceReplaces(context.text) { GoModLayoutContext.hasGoMod(context.dir, it) }
        return replaces.filter { it.line < context.document.lineCount }.map { r ->
            manager.createProblemDescriptor(file, context.range(r.line, r.newPath), MESSAGE, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, CreateGoWorkFix())
        }.toTypedArray()
    }

    private fun hasGoWorkAbove(dir: VirtualFile?): Boolean = generateSequence(dir) { it.parent }.any { it.findChild(GoModFileType.GO_WORK)?.isDirectory == false }

    class CreateGoWorkFix : LocalQuickFix {
        override fun getFamilyName(): String = "Create go.work"

        // creates a file: the preview of a copy cannot show that
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile ?: return
            val dir = file.virtualFile?.parent ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val (work, lines) = GoModLayout.migrateToWorkspace(document.text) { GoModLayoutContext.hasGoMod(dir, it) } ?: return
            val goWork = dir.findChild(GoModFileType.GO_WORK) ?: dir.createChildData(this, GoModFileType.GO_WORK)
            VfsUtil.saveText(goWork, work)
            GoModDirectiveIntention.replaceLines(project, document, lines)
        }
    }

    companion object {
        const val MESSAGE = "Migration to Go workspace is possible"
    }
}

/** `VgoUnresolvedIgnorePath`: a path of an `ignore` directive (Go 1.25) that names no directory of the module. */
class GoModUnresolvedIgnorePathInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val context = GoModLayoutContext.of(file) ?: return null
        val dir = context.dir ?: return null
        val unresolved = GoModLayout.unresolvedIgnores(context.text, { GoModLayoutContext.resolve(dir, it) != null }, { existsAnywhere(dir, it) })
        return unresolved.filter { it.line < context.document.lineCount }.map { p ->
            manager.createProblemDescriptor(file, context.range(p.line, p.path), "Unresolved path '${p.path}' in 'ignore' directive", ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly, RemoveIgnorePathFix(p.line, p.path))
        }.toTypedArray()
    }

    /** A directory at [path] below any directory of the module; null when the module is too large to walk in an inspection. */
    private fun existsAnywhere(root: VirtualFile, path: String): Boolean? {
        val first = path.substringBefore('/')
        val rest = path.substringAfter('/', "")
        var seen = 0
        var found = false
        var gaveUp = false
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (found || gaveUp) return false
                if (++seen > WALK_LIMIT) { gaveUp = true; return false }
                if (!file.isDirectory) return false
                if (file != root && file.name.startsWith(".")) return false
                if (file.name == first && file != root && (rest.isEmpty() || VfsUtilCore.findRelativeFile(rest, file) != null)) found = true
                return !found
            }
        })
        return if (found) true else if (gaveUp) null else false
    }

    class RemoveIgnorePathFix(private val line: Int, private val path: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Remove the path"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) = GoModLayoutContext.apply(project, descriptor) { lines ->
            // the document may have changed since the check
            if (line in lines.indices && path in lines[line]) GoModLayout.removeIgnore(lines, line) else null
        }
    }

    private companion object {
        const val WALK_LIMIT = 20_000
    }
}
