package io.github.golangsupport.mod

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent

/** A go.mod / go.work intention that rewrites the directives around the caret line by [GoModDirectiveEdits]; text only, so also while indexing. */
abstract class GoModDirectiveIntention(private val title: String) : IntentionAction, DumbAware {

    /** The new lines of the file for the caret on [line], or null when the intention does not apply there. */
    protected abstract fun edit(lines: List<String>, line: Int): List<String>?

    override fun getText(): String = title

    override fun getFamilyName(): String = title

    override fun startInWriteAction(): Boolean = true

    private fun document(project: Project, file: PsiFile?): Document? =
        (file as? GoModPsiFile)?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val document = document(project, file) ?: return false
        if (editor == null) return false
        return edit(document.immutableCharSequence.split('\n'), document.getLineNumber(editor.caretModel.offset)) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = document(project, file) ?: return
        if (editor == null) return
        val new = edit(document.immutableCharSequence.split('\n'), document.getLineNumber(editor.caretModel.offset)) ?: return
        replaceLines(project, document, new)
    }

    companion object {
        /** Puts [new] lines into [document] by the smallest replacement ([GoModDirectiveEdits.change]); the intentions and the go.mod fixes share it. */
        fun replaceLines(project: Project, document: Document, new: List<String>) {
            val old = document.immutableCharSequence.split('\n')
            val (from, to, lines) = GoModDirectiveEdits.change(old, new)
            when {
                from >= old.size -> document.insertString(document.textLength, lines.joinToString("") { "\n" + it })
                to < old.size -> document.replaceString(document.getLineStartOffset(from), document.getLineStartOffset(to), lines.joinToString("") { it + "\n" })
                // the tail of the file: from the break before the first changed line, or the last kept line keeps a break too many
                from > 0 -> document.replaceString(document.getLineEndOffset(from - 1), document.textLength, lines.joinToString("") { "\n" + it })
                else -> document.replaceString(0, document.textLength, lines.joinToString("\n"))
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

class GoModMergeGroupIntention : GoModDirectiveIntention("Merge a group of directives") {
    override fun edit(lines: List<String>, line: Int): List<String>? = GoModDirectiveEdits.mergeGroup(lines, line)
}

class GoModMergeAllIntention : GoModDirectiveIntention("Merge all directives") {
    override fun edit(lines: List<String>, line: Int): List<String>? = GoModDirectiveEdits.mergeAll(lines, line)
}

class GoModMergeUpIntention : GoModDirectiveIntention("Merge directive up") {
    override fun edit(lines: List<String>, line: Int): List<String>? = GoModDirectiveEdits.mergeUp(lines, line)
}

/** A direct require with the newer version the module proxy knows for it. */
data class GoModUpdate(val require: GoRequire, val version: String) {
    val target: String get() = "${require.path}@$version"
}

/**
 * "Update dependencies…" on a `require` of go.mod: a dialog with the direct requires that have a newer version ([GoModUpdates], the
 * same data as the inspection), all checked; OK runs `go get path@version` for the chosen ones in the background. Offered only when
 * at least one update is known: the versions come from a background `go list` started by the highlighting of the file.
 */
class GoModUpdateDependenciesIntention : IntentionAction {
    override fun getText(): String = TITLE

    override fun getFamilyName(): String = TITLE

    override fun startInWriteAction(): Boolean = false

    // Opens a dialog and runs `go get`: nothing to preview.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null && updates(project, editor, file).isNotEmpty()

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null) return
        val updates = updates(project, editor, file)
        if (updates.isEmpty()) return
        val dir = file?.virtualFile?.parent?.path ?: return
        val chosen = chooser(project, updates)?.takeIf { it.isNotEmpty() } ?: return
        runner(project, dir, chosen.map { it.target })
    }

    private fun updates(project: Project, editor: Editor, file: PsiFile?): List<GoModUpdate> {
        if (file !is GoModPsiFile || file.name != GoModFileType.GO_MOD) return emptyList()
        val path = file.virtualFile?.path ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return emptyList()
        val text = document.immutableCharSequence
        GoModDirectiveEdits.requireAt(text.split('\n'), document.getLineNumber(editor.caretModel.offset)) ?: return emptyList()
        val requires = GoModFile.parse(text).requires
        val known = updatesSource(project, path, requires) ?: return emptyList()
        return GoModUpdates.outdated(requires.filter { !it.indirect }, known).map { (require, version) -> GoModUpdate(require, version) }
    }

    companion object {
        const val TITLE = "Update dependencies…"

        internal var updatesSource: (Project, String, List<GoRequire>) -> Map<String, String>? = GoModUpdates::updates
            @TestOnly set

        internal var chooser: (Project, List<GoModUpdate>) -> List<GoModUpdate>? = { project, updates ->
            GoModUpdateDialog(project, updates).takeIf { it.showAndGet() }?.selected()
        }
            @TestOnly set

        internal var runner: (Project, String, List<String>) -> Unit = { project, dir, targets -> GoModUpdates.goGet(project, dir, "Update Dependencies", targets) }
            @TestOnly set
    }
}

/** The direct requires with newer versions, each with a check box (all checked). */
class GoModUpdateDialog(project: Project, private val updates: List<GoModUpdate>) : DialogWrapper(project) {
    private val list = CheckBoxList<GoModUpdate>()

    init {
        title = "Update Dependencies"
        for (u in updates) list.addItem(u, "${u.require.path}  ${u.require.version} → ${u.version}", true)
        setOKButtonText("Update")
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(list).apply { preferredSize = JBUI.size(560, 320) }

    fun selected(): List<GoModUpdate> = updates.filter { list.isItemSelected(it) }
}
