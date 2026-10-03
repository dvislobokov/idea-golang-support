package io.github.golangsupport.lint

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.lint.GoUncheckedErrorInspection
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoReorderFieldsIntention
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One finding of golangci-lint or of a custom linter; [line] and [column] are one-based, the column is 0 when the linter names only the line.
 * [rule]: the `ruleId` of a SARIF result (a golangci report names the linter instead, in [linter]).
 */
data class GoLintIssue(val file: String, val line: Int, val column: Int, val text: String, val linter: String, val isError: Boolean, val rule: String = "")

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
    fun parse(stdout: String): List<GoLintIssue> = parseReport(stdout).orEmpty()

    /** null when stdout has no report at all (the tool has failed); empty when the report has no issues. A pretty-printed report is read whole. */
    fun parseReport(stdout: String): List<GoLintIssue>? {
        val candidates = stdout.lineSequence().map { it.trim() }.filter { it.startsWith("{") } + sequenceOf(stdout.trim()).filter { it.startsWith("{") }
        val report = candidates.firstNotNullOfOrNull { text -> runCatching { JsonParser.parseString(text) as? JsonObject }.getOrNull()?.takeIf { it.has("Issues") } } ?: return null
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
 * Findings of external linters that a native inspection already reports while typing: dropped, so one place is not underlined twice.
 * Only for the rule families the table knows; whatever else a linter says stays.
 */
object GoLintDuplicates {
    /** Linter (the `FromLinter` of the report, or the name of a custom linter) -> the inspections of go-psi-ide that report the same thing. */
    private val NATIVE: Map<String, List<String>> = mapOf(
        "errcheck" to listOf(GoUncheckedErrorInspection.SHORT_NAME),
        "ineffassign" to listOf("GoIneffectualAssignment"),
        "unused" to listOf("GoUnusedVariable", "GoUnusedParameter"),
        "printf" to listOf("GoPrintf"),
    )

    /** The native inspections that cover a finding of [linter] with [text]; `govet` is many analyzers, its printf findings begin with `printf:`. */
    fun nativeInspections(linter: String, text: String): List<String> = when {
        linter == "govet" && text.startsWith("printf:") -> NATIVE.getValue("printf")
        else -> NATIVE[linter].orEmpty()
    }

    /**
     * Whether the finding of [linter] at [offset]..[end] is reported by a native inspection that is on for [file]. errcheck is decided by the
     * inspection's own rule on the call at [offset] (the callee name: errcheck points at the parenthesis, [GoLintOutput.rangeInLine] moves back
     * to the name), so what it leaves alone (`defer f.Close()`, a call it cannot resolve) keeps the warning. The other families look for a
     * highlight of their inspection over the range in the editor. Under the read action of the annotator.
     */
    fun reportedNatively(file: PsiFile, offset: Int, linter: String, text: String = "", end: Int = offset): Boolean {
        val inspections = nativeInspections(linter, text)
        if (inspections.isEmpty() || file !is GoFile || DumbService.isDumb(file.project)) return false
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        val enabled = inspections.filter { name -> HighlightDisplayKey.find(name)?.let { profile.isToolEnabled(it, file) } == true }
        if (enabled.isEmpty()) return false
        if (linter == "errcheck") {
            val call = PsiTreeUtil.getParentOfType(file.findElementAt(offset), GoCallExpr::class.java, false) ?: return false
            return GoUncheckedErrorInspection.isUnchecked(call)
        }
        val document = file.viewProvider.document ?: return false
        var found = false
        DaemonCodeAnalyzerEx.processHighlights(document, file.project, null, offset, maxOf(end, offset + 1)) { info ->
            found = info.inspectionToolId in enabled
            !found
        }
        return found
    }
}

/** Where the findings of an external tool go in the editor: they are positions in the saved text, kept as range markers so that they follow the edits. */
internal object GoLintPlacement {
    /** With [fresh] findings the markers of the last run are replaced; otherwise the findings stand where their markers have moved to. */
    fun <T> place(virtualFile: VirtualFile, document: Document, fresh: List<T>?, key: Key<List<Pair<RangeMarker, T>>>, line: (T) -> Int, column: (T) -> Int): List<Pair<TextRange, T>> {
        if (fresh == null) return virtualFile.getUserData(key).orEmpty().filter { it.first.isValid }.map { (marker, item) -> marker.textRange to item }.filter { !it.first.isEmpty }
        virtualFile.getUserData(key)?.forEach { it.first.dispose() }
        val placed = fresh.mapNotNull { item -> rangeOf(document, line(item), column(item))?.let { it to item } }
        virtualFile.putUserData(key, placed.map { (range, item) -> document.createRangeMarker(range) to item })
        return placed
    }

    fun rangeOf(document: Document, oneBasedLine: Int, column: Int): TextRange? {
        val line = oneBasedLine - 1
        if (line !in 0 until document.lineCount) return null
        val start = document.getLineStartOffset(line)
        val inLine = GoLintOutput.rangeInLine(document.immutableCharSequence.subSequence(start, document.getLineEndOffset(line)), column)
        return TextRange(start + inLine.first, start + inLine.last + 1).takeIf { !it.isEmpty }
    }

    /** The issues of a report about [file]: linters name files relative to their working directory, or absolute. */
    fun ofFile(issues: List<GoLintIssue>, workDirectory: String, file: String): List<GoLintIssue> =
        issues.filter { FileUtil.pathsEqual(File(workDirectory, it.file).path, file) || FileUtil.pathsEqual(it.file, file) }
}

/**
 * Warnings of golangci-lint in the editor while [GoSettings.golangciLint] is on (off by default: the native inspections are the analysis
 * of the plugin), with the configuration of the repository (`.golangci.yml`), for files that are saved:
 * the linter reads the disk, and positions in a changed text would point at the wrong code. Runs after the syntax pass, in the
 * background; the language server keeps reporting what it knows while typing.
 */
class GoLintAnnotator : ExternalAnnotator<GoLintAnnotator.Request, GoLintAnnotator.Result>() {
    /** [stale]: the text has changed since the save, so the last findings are shown where their markers have moved to, and nothing is run. */
    class Request(val file: VirtualFile, val stamp: Long, val executable: File, val workDirectory: VirtualFile, val packagePath: String, val stale: Boolean)

    class Result(val issues: List<GoLintIssue>, val fresh: Boolean)

    /** With errors in the file as well: the default skips such files, and half of what a linter says is about code that is being written. */
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Request? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Request? {
        if (file !is GoFile || !GoSettings.getInstance().golangciLint) return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        val modified = FileDocumentManager.getInstance().isFileModified(virtualFile)
        // typed on since the save: the findings of the saved text stay, moved along with the code, until the next save (they used to vanish at the first keystroke)
        if (modified && virtualFile.getUserData(MARKERS).isNullOrEmpty()) return null
        val executable = GoTool.GOLANGCI_LINT.find() ?: return null
        val directory = virtualFile.parent ?: return null
        val root = GoModulesService.getInstance(file.project).moduleOf(virtualFile)?.root ?: directory
        val relative = VfsUtilCore.getRelativePath(directory, root).orEmpty()
        return Request(virtualFile, virtualFile.modificationStamp, executable, root, if (relative.isEmpty()) "." else "./$relative", modified)
    }

    override fun doAnnotate(request: Request): Result {
        if (request.stale) return Result(emptyList(), fresh = false)
        request.file.getUserData(RESULT)?.takeIf { it.first == request.stamp }?.let { return Result(it.second, fresh = true) }
        return try {
            val arguments = GoLintOutput.arguments(majorVersion(request.executable), GoSettings.getInstance().tagList(), request.packagePath)
            val output = GoCli.execute(GoCli.toolCommandLine(request.executable.path, request.workDirectory.path, *arguments.toTypedArray()), GoSettings.getInstance().lintTimeoutSeconds * 1000)
            if (output.isTimeout) return Result(emptyList(), fresh = false)
            val all = GoLintOutput.parse(output.stdout)
            GoPluginLog.info("lint", "golangci-lint ${request.packagePath} in ${request.workDirectory.path}: exit code ${output.exitCode}, ${all.size} issues")
            if (all.isEmpty() && output.exitCode != 0) GoPluginLog.warn("lint", "golangci-lint has failed with exit code ${output.exitCode}: ${output.stderr.lines().lastOrNull { it.isNotBlank() }.orEmpty()}")
            val issues = GoLintPlacement.ofFile(all, request.workDirectory.path, request.file.path)
            request.file.putUserData(RESULT, request.stamp to issues)
            Result(issues, fresh = true)
        } catch (e: Exception) {
            GoPluginLog.warn("lint", "golangci-lint has failed: ${e.message}")
            Result(emptyList(), fresh = false)
        }
    }

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        val virtualFile = file.virtualFile ?: return
        val placed = GoLintPlacement.place(virtualFile, document, result.issues.takeIf { result.fresh }, MARKERS, { it.line }, { it.column })
        for ((range, issue) in placed) {
            if (GoLintDuplicates.reportedNatively(file, range.startOffset, issue.linter, issue.text, range.endOffset)) continue
            val line = document.getLineNumber(range.startOffset)
            val message = if (issue.linter.isEmpty()) issue.text else "${issue.linter}: ${issue.text}"
            var annotation = holder.newAnnotation(if (issue.isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING, message).range(range)
            if (issue.linter == "errcheck") annotation = annotation.withFix(GoErrcheckFix(handle = true, line)).withFix(GoErrcheckFix(handle = false, line))
            if (issue.linter == "govet" && "fieldalignment" in issue.text) annotation = annotation.withFix(GoReorderFieldsIntention(line))
            // a finding of the type checker is an error of the code, not an opinion to argue with
            if (issue.linter.isNotEmpty() && issue.linter != "typecheck") annotation = annotation.withFix(GoNolintFix(issue.linter, line))
            annotation.create()
        }
    }

    private companion object {
        val RESULT: Key<Pair<Long, List<GoLintIssue>>> = Key.create("io.github.golangsupport.lint.result")
        /** Where the findings of the last run stand now: the markers follow the edits, so the warnings stay put while typing. */
        val MARKERS: Key<List<Pair<RangeMarker, GoLintIssue>>> = Key.create("io.github.golangsupport.lint.markers")
        val VERSIONS = ConcurrentHashMap<String, Int>()

        fun majorVersion(executable: File): Int = VERSIONS.getOrPut(executable.path + ":" + executable.lastModified()) {
            GoLintOutput.majorVersion(runCatching { GoCli.execute(GoCli.toolCommandLine(executable.path, null, "version"), 15_000).stdout }.getOrDefault(""))
        }
    }
}
