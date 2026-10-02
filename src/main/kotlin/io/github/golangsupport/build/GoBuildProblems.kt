package io.github.golangsupport.build

import com.intellij.build.events.MessageEvent
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lint.GoLintOutput
import io.github.golangsupport.settings.GoLanguageServerControl
import io.github.golangsupport.settings.GoSettings
import java.util.concurrent.ConcurrentHashMap

/** A message of the last build or vet, kept by the file it names: [isError] for the compiler, a warning for vet. */
class GoBuildProblem(val message: GoBuildMessage, val isError: Boolean)

/**
 * What the last `go build` / `go vet` said, by file, for the editor of an IDE without the language server (the LSP module is not in every
 * IDE, and the server can be switched off): the errors of the compiler are then the errors of the editor, until the file is edited or built again.
 */
@Service(Service.Level.PROJECT)
class GoBuildProblems(private val project: Project) {
    private val problems = ConcurrentHashMap<String, MutableList<GoBuildProblem>>()

    fun of(path: String): List<GoBuildProblem> = problems[FileUtil.toSystemIndependentName(path)].orEmpty()

    /** A build of [workDirectory] starts: what it said last time about the files there is void. */
    fun clear(workDirectory: String?) {
        val prefix = workDirectory?.let { FileUtil.toSystemIndependentName(it).trimEnd('/') + "/" }
        if (prefix == null) problems.clear() else problems.keys.removeIf { it.startsWith(prefix) }
    }

    fun add(message: GoBuildMessage, workDirectory: String?, kind: MessageEvent.Kind) {
        val path = FileUtil.toSystemIndependentName(message.resolveFile(workDirectory).path)
        problems.getOrPut(path) { java.util.Collections.synchronizedList(ArrayList()) } += GoBuildProblem(message, kind == MessageEvent.Kind.ERROR)
    }

    /** The build is over: the editors show what it found. */
    fun finished() {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart() }, ModalityState.any())
    }

    companion object {
        fun getInstance(project: Project): GoBuildProblems = project.service()

        /**
         * Whether the editor shows the messages of the last build in [project]. The language server takes over as soon as it is there and
         * switched on: two underlines for one error help nobody. With the native PSI as the source of diagnostics gopls no longer underlines
         * compiler errors ([io.github.golangsupport.lang.GoFeature.DIAGNOSTICS]), and the messages of an explicit build are the compiler's
         * word on what the checker may have missed, so they come back.
         */
        fun shouldShow(project: Project): Boolean =
            shouldShow(GoLanguageServerControl.EP.extensionList.isEmpty(), GoSettings.getInstance().languageServerEnabled, GoFeatures.native(GoFeature.DIAGNOSTICS, project))

        /** The pure rule of [shouldShow]: no server module, the server off, or the diagnostics served natively. */
        fun shouldShow(noLanguageServerModule: Boolean, languageServerEnabled: Boolean, nativeDiagnostics: Boolean): Boolean =
            noLanguageServerModule || !languageServerEnabled || nativeDiagnostics
    }
}

/** The messages of the last build in the editor, for saved files only: the build read the disk, and its lines mean nothing in a changed text. */
class GoBuildProblemsAnnotator : ExternalAnnotator<List<GoBuildProblem>, List<GoBuildProblem>>() {
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): List<GoBuildProblem>? = collectInformation(file)

    override fun collectInformation(file: PsiFile): List<GoBuildProblem>? {
        if (file !is GoFile || !GoBuildProblems.shouldShow(file.project)) return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        if (FileDocumentManager.getInstance().isFileModified(virtualFile)) return null
        return GoBuildProblems.getInstance(file.project).of(virtualFile.path).takeIf { it.isNotEmpty() }
    }

    override fun doAnnotate(collectedInfo: List<GoBuildProblem>): List<GoBuildProblem> = collectedInfo

    override fun apply(file: PsiFile, problems: List<GoBuildProblem>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        for (problem in problems) {
            val line = problem.message.line - 1
            if (line !in 0 until document.lineCount) continue
            val start = document.getLineStartOffset(line)
            val inLine = GoLintOutput.rangeInLine(document.immutableCharSequence.subSequence(start, document.getLineEndOffset(line)), problem.message.column)
            val range = TextRange(start + inLine.first, start + inLine.last + 1).takeIf { !it.isEmpty } ?: continue
            holder.newAnnotation(if (problem.isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING, problem.message.text).range(range).create()
        }
    }
}
