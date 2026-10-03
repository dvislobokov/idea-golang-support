package io.github.golangsupport.mod

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * "Update all dependencies" and "Update direct dependencies" above the first `require` of a go.mod, as GoLand shows them: each runs
 * `go get module@latest` for the requires (then `go mod vendor` when the module vendors). Not gopls: it is off by default.
 */
abstract class GoModUpdateCodeVisionProvider(private val title: String, private val direct: Boolean) : DaemonBoundCodeVisionProvider {
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Top
    override val groupId: String get() = "go.mod.updates"

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file !is GoModPsiFile || file.name != GoModFileType.GO_MOD) return emptyList()
        val document = editor.document
        val requires = GoModFile.parse(document.immutableCharSequence).requires
        val targets = GoModCodeVision.targets(requires, direct)
        if (targets.isEmpty()) return emptyList()
        val line = GoModCodeVision.anchorLine(document.immutableCharSequence) ?: return emptyList()
        if (line >= document.lineCount) return emptyList()
        val range = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
        val dir = file.virtualFile?.parent?.path ?: return emptyList()
        val entry = ClickableTextCodeVisionEntry(title, id, { _, clicked -> GoModUpdates.goGet(clicked.project ?: return@ClickableTextCodeVisionEntry, dir, title, targets) },
            AllIcons.Actions.Refresh, title, title, emptyList())
        return listOf(range to entry)
    }
}

class GoModUpdateAllCodeVisionProvider : GoModUpdateCodeVisionProvider("Update all dependencies", direct = false) {
    override val id: String get() = "go.mod.update.all"
    override val name: String get() = "Update all dependencies (go.mod)"
}

class GoModUpdateDirectCodeVisionProvider : GoModUpdateCodeVisionProvider("Update direct dependencies", direct = true) {
    override val id: String get() = "go.mod.update.direct"
    override val name: String get() = "Update direct dependencies (go.mod)"
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter("go.mod.update.all"))
}

/** Pure parts, for the tests. */
object GoModCodeVision {
    /** `path@latest` for the requires: all of them, or the direct ones only. */
    fun targets(requires: List<GoRequire>, direct: Boolean): List<String> =
        requires.filter { !direct || !it.indirect }.map { it.path }.distinct().map { "$it@latest" }

    /** The zero-based line of the first `require` directive, or null. */
    fun anchorLine(text: CharSequence): Int? =
        text.lines().indexOfFirst { it.trimStart().startsWith("require") && it.trimStart().removePrefix("require").let { r -> r.isEmpty() || r[0] == ' ' || r[0] == '\t' || r[0] == '(' } }
            .takeIf { it >= 0 }
}
