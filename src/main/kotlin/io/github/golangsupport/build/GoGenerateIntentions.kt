package io.github.golangsupport.build

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Alt+Enter on a `//go:generate` line, GoLand's three intentions: this directive alone (`go generate -run '^<directive>$' file.go`, the
 * gutter's ▶), every directive of the file (`go generate file.go`) or of its package (`go generate .`), all in the file's directory into
 * the Build tool window through [GoGenerateDirectives.run]. Offered on directive lines only, as GoLand does; text only, so also while indexing.
 */
abstract class GoGenerateIntention(private val title: String) : IntentionAction, DumbAware {

    /** The `go` arguments for the directive [directive] of the file named [fileName]. */
    abstract fun arguments(directive: String, fileName: String): List<String>

    override fun getText(): String = title

    override fun getFamilyName(): String = title

    override fun startInWriteAction(): Boolean = false

    // Runs a process: nothing to preview.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null && file != null && directiveAt(file, editor.caretModel.offset) != null

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
    }
}

class GoRunGenerateOnCommentIntention : GoGenerateIntention("Run go generate on comment") {
    override fun arguments(directive: String, fileName: String): List<String> = GoGenerateDirectives.directiveArguments(directive, fileName)
}

class GoRunGenerateOnFileIntention : GoGenerateIntention("Run go generate on file") {
    override fun arguments(directive: String, fileName: String): List<String> = GoGenerateDirectives.fileArguments(fileName)
}

/** `.` in the file's directory: its package, with the files `go generate` selects by build constraints. */
class GoRunGenerateOnPackageIntention : GoGenerateIntention("Run go generate on package") {
    override fun arguments(directive: String, fileName: String): List<String> = listOf("generate", ".")
}
