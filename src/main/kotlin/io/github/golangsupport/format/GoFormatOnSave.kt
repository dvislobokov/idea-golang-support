package io.github.golangsupport.format

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.lang.LanguageFormatting
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.settings.GoFormatter
import io.github.golangsupport.settings.GoSettings
import java.nio.charset.StandardCharsets

/** The smallest replacement that turns one text into another: what is the same at both ends stays untouched, and with it carets, folds and breakpoints there. */
object GoTextDiff {
    class Replacement(val start: Int, val end: Int, val text: String)

    fun minimal(old: CharSequence, new: CharSequence): Replacement? {
        if (old.length == new.length && old.toString() == new.toString()) return null
        var prefix = 0
        val max = minOf(old.length, new.length)
        while (prefix < max && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return Replacement(prefix, old.length - suffix, new.subSequence(prefix, new.length - suffix).toString())
    }
}

/**
 * gofmt (or goimports) over every Go file that is being saved: unformatted Go does not pass a review anywhere, so the formatter runs
 * where other languages ask. The save itself is not held back: the platform forbids waiting for a process on EDT (seen live, an error in
 * the log), so the file is saved as typed, formatted in the background, and saved again if the text has not changed meanwhile. A file
 * the formatter cannot parse stays as it is. The Built-in formatter ([GoFormatter.NATIVE]) runs no process: the file is reformatted
 * before the save by the platform engine, the way the platform's own "Reformat code" on save does it ([ReformatCodeProcessor]).
 */
class GoFormatOnSave : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean = GoSettings.getInstance().let { it.formatOnSave && it.formatter != GoFormatter.NONE }

    override fun processDocuments(project: Project, documents: Array<Document>) {
        val files = FileDocumentManager.getInstance()
        if (GoSettings.getInstance().formatter == GoFormatter.NATIVE) return reformatNatively(project, documents.mapNotNull { document ->
            files.getFile(document)?.takeIf { it.fileType == GoFileType }?.let { document }
        })
        for (document in documents) {
            val file = files.getFile(document)?.takeIf { it.fileType == GoFileType } ?: continue
            val text = document.immutableCharSequence.toString()
            val stamp = document.modificationStamp
            val directory = file.parent?.path
            val local = GoFormattingService.localPrefixes(project, file)
            ApplicationManager.getApplication().executeOnPooledThread {
                val formatter = GoFormattingService.formatter() ?: return@executeOnPooledThread
                val replacement = format(formatter, directory, local, text)?.let { GoTextDiff.minimal(text, it) } ?: return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({
                    // typed on since: the next save formats what is there then
                    if (document.modificationStamp != stamp || !file.isValid) return@invokeLater
                    WriteCommandAction.runWriteCommandAction(project, "Format Go File on Save", null, { document.replaceString(replacement.start, replacement.end, replacement.text) })
                    // the second save finds the text formatted and changes nothing: no loop
                    files.saveDocument(document)
                }, project.disposed)
            }
        }
    }

    /** On the EDT, before the save: the gofmt port of go-psi-ide through the platform engine, whole files (gofmt knows no changed-lines mode either). */
    private fun reformatNatively(project: Project, documents: List<Document>) {
        val psiDocuments = PsiDocumentManager.getInstance(project)
        val psiFiles = documents.mapNotNull { document ->
            psiDocuments.commitDocument(document)
            psiDocuments.getPsiFile(document)?.takeIf { LanguageFormatting.INSTANCE.forContext(it) != null }
        }
        if (psiFiles.isNotEmpty()) ReformatCodeProcessor(project, psiFiles.toTypedArray(), null, false).run()
    }

    private fun format(executable: java.io.File, directory: String?, local: List<String>, text: String): String? = try {
        val handler = CapturingProcessHandler(GoFormattingService.commandLine(executable, directory, local))
        handler.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
        val output = handler.runProcess(TIMEOUT_MS)
        // a syntax error is the business of the editor, not of saving
        if (output.exitCode == 0 && !output.isTimeout && output.stdout.isNotEmpty()) output.stdout else null
    } catch (e: Exception) {
        GoPluginLog.warn("format", "Format on save has failed: ${e.message}")
        null
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        val LOG = logger<GoFormatOnSave>()
    }
}
