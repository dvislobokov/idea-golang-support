package io.github.golangsupport.build

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.elementType
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.mod.GoModulesService
import java.io.File

/** `//go:generate` lines as `go generate` reads them, and the command lines that run one directive or every directive of a file. */
object GoGenerateDirectives {
    const val PREFIX = "//go:generate"
    const val TITLE = "Go Generate"

    /** As cmd/go `isGoGenerate`: the prefix at the start of the line, then a space or a tab. */
    fun isDirective(line: String): Boolean = line.startsWith("$PREFIX ") || line.startsWith("$PREFIX\t")

    /** Go's `regexp.QuoteMeta`: the same metacharacters, so the pattern means the literal text to RE2. */
    fun quoteMeta(text: String): String = buildString(text.length + 8) { for (c in text) { if (c in META) append('\\'); append(c) } }

    /** `-run` of exactly [directive]: cmd/go matches it against the trimmed source line of each directive. */
    fun runPattern(directive: String): String = "^" + quoteMeta(directive.trim()) + "$"

    fun directiveArguments(directive: String, fileName: String): List<String> = listOf("generate", "-run", runPattern(directive), fileName)

    fun fileArguments(fileName: String): List<String> = listOf("generate", fileName)

    /** A file has directives when one of its lines is one; a quick text check for `update`. */
    fun hasDirectives(text: CharSequence): Boolean = text.lineSequence().any { isDirective(it) }

    private const val META = """\.+*?()|[]{}^$"""

    /**
     * `go generate` with [arguments] in the directory of [file], into the Build tool window; the module (or the directory) is re-read afterwards.
     * `runInBackground` saves the documents first: `go generate` reads the file from disk.
     */
    fun run(project: Project, file: VirtualFile, arguments: List<String>) {
        val dir = file.parent ?: return
        val commands = GoCli.commandLinesOrNotify(project, TITLE) { listOf(GoCli.commandLine(dir.path, *arguments.toTypedArray())) } ?: return
        val root = GoModulesService.getInstance(project).moduleOf(file)?.root ?: dir
        GoCli.runInBackground(project, TITLE, commands, refresh = listOf(File(root.path)))
    }
}

/**
 * ▶ on each `//go:generate` line of a Go file, as GoLand's: tooltip `Run go generate on comment`, a click runs this directive alone. A plain line
 * marker without actions, not a run-line contributor: those actions would join Alt+Enter, where GoLand shows only its three intentions
 * ([GoGenerateIntention]; seen live 2026-10-05, ours had five). Text only, so it works while indexing.
 */
class GoGenerateLineMarkerProvider : LineMarkerProvider, DumbAware {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!isDirective(element)) return null
        val pointer = SmartPointerManager.createPointer(element as PsiComment)
        val handler = GutterIconNavigationHandler<PsiComment> { _, _ -> GoGenerateDirectiveAction.run(element.project, pointer) }
        return LineMarkerInfo(element, element.textRange, AllIcons.RunConfigurations.TestState.Run, { TOOLTIP }, handler, GutterIconRenderer.Alignment.CENTER) { TOOLTIP }
    }

    companion object {
        const val TOOLTIP = "Run go generate on comment"

        fun isDirective(element: PsiElement): Boolean =
            element is PsiComment && element.elementType == GoTypes.LINE_COMMENT && GoGenerateDirectives.isDirective(element.text) && atLineStart(element)

        /** cmd/go reads a directive only at the start of a line. */
        private fun atLineStart(element: PsiElement): Boolean {
            val start = element.textRange.startOffset
            return start == 0 || element.containingFile.viewProvider.contents[start - 1] == '\n'
        }
    }
}

/** `go generate -run '^<the directive>$' file.go`: this directive alone (and any identical one of the file). */
class GoGenerateDirectiveAction(private val directive: SmartPsiElementPointer<PsiComment>) : AnAction("Run go:generate", null, AllIcons.Actions.Execute), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        run(e.project ?: return, directive)
    }

    companion object {
        fun run(project: Project, directive: SmartPsiElementPointer<PsiComment>) {
            val (file, text) = ReadAction.compute<Pair<VirtualFile, String>?, Throwable> {
                directive.element?.let { c -> c.containingFile?.virtualFile?.let { it to c.text } }
            } ?: return
            if (!GoGenerateDirectives.isDirective(text)) return
            GoGenerateDirectives.run(project, file, GoGenerateDirectives.directiveArguments(text, file.name))
        }
    }
}

/** Go | Generate File: `go generate file.go`, every directive of the file of the editor (or of the gutter ▶ it was created for). */
class GoGenerateFileAction(private val fixed: VirtualFile? = null) : AnAction(), DumbAware {
    init {
        if (fixed != null) templatePresentation.apply { text = "go generate ${fixed.name}"; icon = AllIcons.Actions.Execute }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun file(e: AnActionEvent): VirtualFile? = fixed ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory && it.name.endsWith(".go") }

    override fun update(e: AnActionEvent) {
        val file = file(e)
        val enabled = e.project != null && file != null && (fixed != null || hasDirectives(file))
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = enabled else e.presentation.isEnabled = enabled
    }

    private fun hasDirectives(file: VirtualFile): Boolean =
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { GoGenerateDirectives.hasDirectives(it.immutableCharSequence) }
            ?: runCatching { GoGenerateDirectives.hasDirectives(String(file.contentsToByteArray(), file.charset)) }.getOrDefault(false)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = file(e) ?: return
        GoGenerateDirectives.run(project, file, GoGenerateDirectives.fileArguments(file.name))
    }
}
