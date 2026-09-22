package io.github.golangsupport.lint

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One finding of golangci-lint; [line] and [column] are one-based, the column is 0 when the linter names only the line. */
data class GoLintIssue(val file: String, val line: Int, val column: Int, val text: String, val linter: String, val isError: Boolean)

/** The command line and the JSON report of golangci-lint; v1 and v2 name the option of the report differently. */
object GoLintOutput {
    /** `golangci-lint has version 2.13.2 built with ...`, `golangci-lint has version v1.64.8 ...`. */
    fun majorVersion(versionOutput: String): Int = Regex("""version v?(\d+)\.""").find(versionOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 2

    fun arguments(majorVersion: Int, buildTags: List<String>, packagePath: String): List<String> = buildList {
        add("run")
        if (majorVersion >= 2) addAll(listOf("--output.json.path=stdout", "--show-stats=false")) else add("--out-format=json")
        // the findings of the whole package are asked for: a linter needs the types of the package, a single file would not compile
        add("--issues-exit-code=0")
        if (buildTags.isNotEmpty()) add("--build-tags=" + buildTags.joinToString(","))
        add(packagePath)
    }

    /** The report is the line of stdout that is a JSON object; what else is printed (warnings of the tool) is skipped. */
    fun parse(stdout: String): List<GoLintIssue> {
        val report = stdout.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
            .firstNotNullOfOrNull { line -> runCatching { JsonParser.parseString(line) as? JsonObject }.getOrNull()?.takeIf { it.has("Issues") } } ?: return emptyList()
        val issues = report.get("Issues")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return issues.mapNotNull { element ->
            val issue = element as? JsonObject ?: return@mapNotNull null
            val position = issue.get("Pos") as? JsonObject ?: return@mapNotNull null
            GoLintIssue(
                file = position.string("Filename") ?: return@mapNotNull null,
                line = position.get("Line")?.asInt ?: return@mapNotNull null,
                column = position.get("Column")?.asInt ?: 0,
                text = issue.string("Text").orEmpty(),
                linter = issue.string("FromLinter").orEmpty(),
                isError = issue.string("Severity") == "error",
            )
        }
    }

    /** What of the line to underline: from the column to the end of the word there, or the whole line without its indent. */
    fun rangeInLine(lineText: CharSequence, column: Int): IntRange {
        val indent = lineText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val end = lineText.trimEnd().length
        if (column < 1 || column > end) return indent until end.coerceAtLeast(indent + 1)
        val at = column - 1
        var wordEnd = at
        while (wordEnd < end && isWordPart(lineText[wordEnd])) wordEnd++
        if (wordEnd > at) return at until wordEnd
        // errcheck points at the parenthesis of the call (seen live): the call is what is wrong, from its name on
        var wordStart = at
        while (wordStart > indent && isWordPart(lineText[wordStart - 1])) wordStart--
        return wordStart until end
    }

    private fun isWordPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}

/**
 * Warnings of golangci-lint in the editor, with the configuration of the repository (`.golangci.yml`), for files that are saved:
 * the linter reads the disk, and positions in a changed text would point at the wrong code. Runs after the syntax pass, in the
 * background; the language server keeps reporting what it knows while typing.
 */
class GoLintAnnotator : ExternalAnnotator<GoLintAnnotator.Request, List<GoLintIssue>>() {
    class Request(val file: VirtualFile, val stamp: Long, val executable: File, val workDirectory: VirtualFile, val packagePath: String)

    /** With errors in the file as well: the default skips such files, and half of what a linter says is about code that is being written. */
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Request? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Request? {
        if (file !is GoFile || !GoSettings.getInstance().lintOnTheFly) return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        if (FileDocumentManager.getInstance().isFileModified(virtualFile)) return null
        val executable = GoTool.GOLANGCI_LINT.find() ?: return null
        val directory = virtualFile.parent ?: return null
        val root = GoModulesService.getInstance(file.project).moduleOf(virtualFile)?.root ?: directory
        val relative = VfsUtilCore.getRelativePath(directory, root).orEmpty()
        return Request(virtualFile, virtualFile.modificationStamp, executable, root, if (relative.isEmpty()) "." else "./$relative")
    }

    override fun doAnnotate(request: Request): List<GoLintIssue> {
        request.file.getUserData(RESULT)?.takeIf { it.first == request.stamp }?.let { return it.second }
        return try {
            val arguments = GoLintOutput.arguments(majorVersion(request.executable), GoSettings.getInstance().tagList(), request.packagePath)
            val output = GoCli.execute(GoCli.toolCommandLine(request.executable.path, request.workDirectory.path, *arguments.toTypedArray()), TIMEOUT_MS)
            if (output.isTimeout) return emptyList()
            val all = GoLintOutput.parse(output.stdout)
            LOG.info("golangci-lint ${request.packagePath} in ${request.workDirectory.path}: exit code ${output.exitCode}, ${all.size} issues")
            if (all.isEmpty() && output.exitCode != 0) LOG.info("golangci-lint has failed with exit code ${output.exitCode}: ${output.stderr.lines().lastOrNull { it.isNotBlank() }.orEmpty()}")
            val issues = all.filter { FileUtil.pathsEqual(File(request.workDirectory.path, it.file).path, request.file.path) || FileUtil.pathsEqual(it.file, request.file.path) }
            request.file.putUserData(RESULT, request.stamp to issues)
            issues
        } catch (e: Exception) {
            LOG.info("golangci-lint has failed: ${e.message}")
            emptyList()
        }
    }

    override fun apply(file: PsiFile, issues: List<GoLintIssue>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        for (issue in issues) {
            val range = rangeOf(document, issue) ?: continue
            val message = if (issue.linter.isEmpty()) issue.text else "${issue.linter}: ${issue.text}"
            var annotation = holder.newAnnotation(if (issue.isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING, message).range(range)
            if (issue.linter == "errcheck") annotation = annotation.withFix(GoErrcheckFix(handle = true, issue.line - 1)).withFix(GoErrcheckFix(handle = false, issue.line - 1))
            // a finding of the type checker is an error of the code, not an opinion to argue with
            if (issue.linter.isNotEmpty() && issue.linter != "typecheck") annotation = annotation.withFix(GoNolintFix(issue.linter, issue.line - 1))
            annotation.create()
        }
    }

    private fun rangeOf(document: Document, issue: GoLintIssue): TextRange? {
        val line = issue.line - 1
        if (line !in 0 until document.lineCount) return null
        val start = document.getLineStartOffset(line)
        val inLine = GoLintOutput.rangeInLine(document.immutableCharSequence.subSequence(start, document.getLineEndOffset(line)), issue.column)
        return TextRange(start + inLine.first, start + inLine.last + 1).takeIf { !it.isEmpty }
    }

    private companion object {
        const val TIMEOUT_MS = 90_000
        val LOG = logger<GoLintAnnotator>()
        val RESULT: Key<Pair<Long, List<GoLintIssue>>> = Key.create("io.github.golangsupport.lint.result")
        val VERSIONS = ConcurrentHashMap<String, Int>()

        fun majorVersion(executable: File): Int = VERSIONS.getOrPut(executable.path + ":" + executable.lastModified()) {
            GoLintOutput.majorVersion(runCatching { GoCli.execute(GoCli.toolCommandLine(executable.path, null, "version"), 15_000).stdout }.getOrDefault(""))
        }
    }
}
