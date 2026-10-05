package io.github.golangsupport.build

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.ShortcutProvider
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.mod.GoModulesService

/**
 * Alt+Enter on a `//go:generate` line, GoLand's three intentions (seen live on GoLand 2026.2.3, in this order): `Go Generate File` (every directive
 * of the file), `Go generate '<import path>'` (the file's package) and `Go generate '<command>'` (this directive alone, the gutter's ▶), all in the
 * file's directory into the Build tool window through [GoGenerateDirectives.run]. Offered on directive lines only; text only, so also while indexing.
 * The order is fixed by [PriorityAction]: the popup otherwise sorts by text, and `'echo …'` would come before `'example.com/…'`.
 */
abstract class GoGenerateIntention(private val priority: PriorityAction.Priority) : IntentionAction, PriorityAction, DumbAware {
    private var title: String? = null

    /** The text for the directive [directive] of [file] (its command is [command]). */
    abstract fun title(project: Project, file: VirtualFile?, directive: String, command: String): String

    /** The `go` arguments for the directive [directive] of the file named [fileName]. */
    abstract fun arguments(directive: String, fileName: String): List<String>

    override fun getText(): String = title ?: familyName

    override fun getPriority(): PriorityAction.Priority = priority

    override fun startInWriteAction(): Boolean = false

    // Runs a process: nothing to preview.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        val directive = directiveAt(file, editor.caretModel.offset) ?: return false
        title = title(project, file.virtualFile ?: file.originalFile.virtualFile, directive, command(directive))
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val directive = directiveAt(file, editor.caretModel.offset) ?: return
        val virtualFile = file.virtualFile ?: return
        GoGenerateDirectives.run(project, virtualFile, arguments(directive, virtualFile.name))
    }

    companion object {
        /** The text of the `//go:generate` line comment that starts the line of [offset] (cmd/go reads it only there), or null. */
        fun directiveAt(file: PsiFile, offset: Int): String? {
            val text = file.viewProvider.contents
            var start = minOf(offset, text.length)
            while (start > 0 && text[start - 1] != '\n') start--
            val comment = file.findElementAt(start) as? PsiComment ?: return null
            if (comment.elementType != GoTypes.LINE_COMMENT || comment.textRange.startOffset != start) return null
            return comment.text.takeIf { GoGenerateDirectives.isDirective(it) }
        }

        /** `echo hi` of `//go:generate echo hi`. */
        fun command(directive: String): String = directive.removePrefix(GoGenerateDirectives.PREFIX).trim()
    }
}

/** `go generate file.go`, as Go | Generate File; the shortcut of that action (when the keymap has one) is offered to the popup. */
class GoGenerateFileIntention : GoGenerateIntention(PriorityAction.Priority.HIGH), ShortcutProvider {
    override fun getFamilyName(): String = "Run go generate on file"

    override fun title(project: Project, file: VirtualFile?, directive: String, command: String): String = "Go Generate File"

    override fun arguments(directive: String, fileName: String): List<String> = GoGenerateDirectives.fileArguments(fileName)

    override fun getShortcut(): ShortcutSet? =
        KeymapManager.getInstance()?.activeKeymap?.getShortcuts("Go.GenerateFile")?.takeIf { it.isNotEmpty() }?.let { CustomShortcutSet(*it) }
            ?: ActionManager.getInstance().getAction("Go.GenerateFile")?.shortcutSet?.takeIf { it.shortcuts.isNotEmpty() }
}

/** `go generate .` in the file's directory: its package, with the files `go generate` selects by build constraints; named by its import path. */
class GoGeneratePackageIntention : GoGenerateIntention(PriorityAction.Priority.NORMAL) {
    override fun getFamilyName(): String = "Run go generate on package"

    override fun title(project: Project, file: VirtualFile?, directive: String, command: String): String {
        val directory = file?.parent
        val path = directory?.let { GoModulesService.getInstance(project).moduleOf(it)?.importPath(it) } ?: directory?.name ?: "."
        return "Go generate '$path'"
    }

    override fun arguments(directive: String, fileName: String): List<String> = listOf("generate", ".")
}

/** `go generate -run '^<directive>$' file.go`: this directive alone (and any identical one of the file), named by its command. */
class GoGenerateDirectiveIntention : GoGenerateIntention(PriorityAction.Priority.LOW) {
    override fun getFamilyName(): String = "Run go generate on comment"

    override fun title(project: Project, file: VirtualFile?, directive: String, command: String): String = "Go generate '$command'"

    override fun arguments(directive: String, fileName: String): List<String> = GoGenerateDirectives.directiveArguments(directive, fileName)
}
