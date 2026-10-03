package io.github.golangsupport.lint

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.execution.ParametersListUtil
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoCustomLinter
import io.github.golangsupport.settings.GoLinterDirectory
import io.github.golangsupport.settings.GoLinterFormat
import io.github.golangsupport.settings.GoLinterTrigger
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** The values of the macros of a custom linter's command line for one file; paths with the separators of the system. */
data class GoLinterContext(val filePath: String, val fileDir: String, val moduleDir: String, val packagePattern: String, val importPath: String, val workDirectory: String)

/** A finding of a custom linter as the editor shows it: [key] is the linter the finding is of, for `//nolint:` and for the duplicates table. */
data class GoLinterFinding(val linterName: String, val key: String, val message: String, val issue: GoLintIssue)

/** The pure part of Custom linters (Settings | Tools | Go | Code Quality): macros, reports, messages. Tested without a process. */
object GoCustomLinters {
    /** The macros of a command line, in the order the settings page lists them. */
    val MACROS = listOf("\$FilePath\$", "\$FileDir\$", "\$ModuleDir\$", "\$Package\$", "\$ImportPath\$")

    /**
     * [moduleDir] null: the file is outside any module, so the module macros name the directory of the file. `$Package$` is a package
     * pattern relative to the working directory (`./store`, `.`) — what `go vet`, `staticcheck` and golangci-lint take; `$ImportPath$` is
     * the import path of the package (`example.com/shop/store`), the relative pattern when the module path is not known.
     */
    fun context(filePath: String, moduleDir: String?, modulePath: String?, directory: GoLinterDirectory): GoLinterContext {
        val fileDir = File(filePath).parent ?: filePath
        val module = moduleDir ?: fileDir
        val relative = FileUtil.getRelativePath(File(module), File(fileDir))?.replace('\\', '/')?.takeIf { it != "." && !it.startsWith("..") }.orEmpty()
        val fromModule = if (relative.isEmpty()) "." else "./$relative"
        val workDirectory = if (directory == GoLinterDirectory.MODULE_ROOT) module else fileDir
        val pattern = if (directory == GoLinterDirectory.MODULE_ROOT) fromModule else "."
        val importPath = modulePath?.let { if (relative.isEmpty()) it else "$it/$relative" } ?: fromModule
        return GoLinterContext(filePath, fileDir, module, pattern, importPath, workDirectory)
    }

    /** Split as a shell would (quotes keep spaces), then the macros replaced in each argument: a path with spaces stays one argument. */
    fun expand(commandLine: String, context: GoLinterContext): List<String> = ParametersListUtil.parse(commandLine).map { argument ->
        argument.replace("\$FilePath\$", context.filePath).replace("\$FileDir\$", context.fileDir).replace("\$ModuleDir\$", context.moduleDir)
            .replace("\$Package\$", context.packagePattern).replace("\$ImportPath\$", context.importPath)
    }

    /** `./tools/lint.cmd` is of the working directory: a process looks for a relative executable in the directory of the IDE otherwise. */
    fun executable(first: String, workDirectory: String): String =
        if (('/' in first || '\\' in first) && !File(first).isAbsolute) File(workDirectory, first).path else first

    /** null: stdout has no report of the format, the linter has failed. */
    fun parse(format: GoLinterFormat, stdout: String): List<GoLintIssue>? = when (format) {
        GoLinterFormat.GOLANGCI_JSON -> GoLintOutput.parseReport(stdout)
        GoLinterFormat.SARIF -> GoSarifReader.parse(stdout)
    }

    /** `[mylinter] text`; the linter or rule inside the report goes after the name when it says more: `[ci] errcheck: text`. */
    fun finding(linterName: String, issue: GoLintIssue): GoLinterFinding {
        val inner = issue.linter.takeIf { it.isNotEmpty() && it != linterName } ?: issue.rule.takeIf { it.isNotEmpty() && it != linterName }
        val message = "[$linterName] " + inner?.let { "$it: " }.orEmpty() + issue.text
        return GoLinterFinding(linterName, issue.linter.ifEmpty { linterName }, message, issue)
    }

    /** One balloon per linter and session: a linter that fails fails on every save, and the journal has every time. */
    private val NOTIFIED = ConcurrentHashMap.newKeySet<String>()

    fun failed(project: Project, linterName: String, reason: String) {
        GoPluginLog.warn("lint", "custom linter $linterName: $reason")
        if (NOTIFIED.add(linterName)) GoCli.notifyError(project, "Custom linter $linterName has failed", "$reason\nLater failures of it go to the plugin log only.")
    }
}

/** SARIF 2.1.0 as linters print it: `runs[].results[]` with the first physical location of each; the reading counterpart of `ci.GoSarif`. */
object GoSarifReader {
    /** null when [text] is not a SARIF log (no `runs` array). */
    fun parse(text: String): List<GoLintIssue>? {
        val start = text.indexOf('{').takeIf { it >= 0 } ?: return null
        val root = runCatching { JsonParser.parseString(text.substring(start)) as? JsonObject }.getOrNull() ?: return null
        val runs = root.array("runs") ?: return null
        return runs.flatMap { run -> (run as? JsonObject)?.array("results")?.mapNotNull { result(it as? JsonObject) }.orEmpty() }
    }

    private fun result(result: JsonObject?): GoLintIssue? {
        result ?: return null
        val location = result.array("locations")?.firstOrNull() as? JsonObject ?: return null
        val physical = location.obj("physicalLocation") ?: return null
        val uri = physical.obj("artifactLocation")?.string("uri") ?: return null
        val region = physical.obj("region")
        return GoLintIssue(
            file = path(uri),
            line = region?.int("startLine") ?: 1,
            column = region?.int("startColumn") ?: 0,
            text = result.obj("message")?.string("text").orEmpty(),
            linter = "",
            isError = result.string("level") == "error",
            rule = result.string("ruleId") ?: result.obj("rule")?.string("id").orEmpty(),
        )
    }

    /** `file:///C:/x/a.go` -> `C:\x\a.go`; a relative URI (of `%SRCROOT%`) stays relative to the working directory, decoded. */
    fun path(uri: String): String =
        if (uri.startsWith("file:")) runCatching { Paths.get(URI(uri)).toString() }.getOrElse { uri.removePrefix("file://") }
        else URLDecoder.decode(uri, StandardCharsets.UTF_8)

    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
    private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asInt
}

/**
 * Custom linters in the editor: each enabled row of the table is run for a saved Go file with its macros expanded, its report read and
 * every finding shown as `[name] message`. External tools read the disk, so a changed text is not linted: the findings of the saved text
 * stay where their markers have moved to until the next save, as with golangci-lint.
 */
class GoCustomLintAnnotator : ExternalAnnotator<GoCustomLintAnnotator.Request, GoCustomLintAnnotator.Result>() {
    class Run(val linter: GoCustomLinter, val arguments: List<String>, val workDirectory: String)

    class Request(val project: Project, val file: VirtualFile, val stamp: Long, val runs: List<Run>, val stale: Boolean)

    /** [findings] null: nothing new, the findings of the last run stay where their markers are. */
    class Result(val findings: List<GoLinterFinding>?)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Request? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Request? {
        if (file !is GoFile) return null
        val linters = GoSettings.getInstance().customLinters.filter { it.isRunnable }
        if (linters.isEmpty()) return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        if (FileDocumentManager.getInstance().isFileModified(virtualFile)) {
            return if (virtualFile.getUserData(MARKERS).isNullOrEmpty()) null else Request(file.project, virtualFile, virtualFile.modificationStamp, emptyList(), stale = true)
        }
        val saved = virtualFile.getUserData(GoLintSaveListener.SAVED_IN_SESSION) == true
        val module = GoModulesService.getInstance(file.project).moduleOf(virtualFile)
        val runs = linters.filter { it.trigger == GoLinterTrigger.AFTER_SAVE || saved }.map { linter ->
            val context = GoCustomLinters.context(FileUtil.toSystemDependentName(virtualFile.path), module?.root?.path?.let(FileUtil::toSystemDependentName), module?.path, linter.directory)
            Run(linter, GoCustomLinters.expand(linter.commandLine, context), context.workDirectory)
        }
        return Request(file.project, virtualFile, virtualFile.modificationStamp, runs, stale = false)
    }

    override fun doAnnotate(request: Request): Result {
        if (request.stale) return Result(null)
        val cache = request.file.getUserData(RESULTS) ?: ConcurrentHashMap<String, Pair<Long, List<GoLintIssue>>>().also { request.file.putUserData(RESULTS, it) }
        val findings = request.runs.flatMap { run ->
            // the whole row is the key: a changed command line is another linter
            val key = run.linter.toString()
            val issues = cache[key]?.takeIf { it.first == request.stamp }?.second ?: run(request, run)?.also { cache[key] = request.stamp to it } ?: emptyList()
            issues.map { GoCustomLinters.finding(run.linter.name, it) }
        }
        return Result(findings)
    }

    /** The issues of [run] about the file; null when it has failed (said in the journal, and once per session in a balloon). */
    private fun run(request: Request, run: Run): List<GoLintIssue>? {
        val name = run.linter.name
        if (run.arguments.isEmpty()) return null
        val timeout = GoSettings.getInstance().lintTimeoutSeconds
        return try {
            val commandLine = GoCli.toolCommandLine(GoCustomLinters.executable(run.arguments.first(), run.workDirectory), run.workDirectory, *run.arguments.drop(1).toTypedArray())
            val output = GoCli.execute(commandLine, timeout * 1000)
            if (output.isTimeout) {
                GoCustomLinters.failed(request.project, name, "No answer in $timeout s: ${GoCli.displayString(commandLine)}")
                return null
            }
            // a linter may exit with 1 when it has findings (golangci-lint does): the report decides, not the exit code
            val all = GoCustomLinters.parse(run.linter.format, output.stdout) ?: if (output.stdout.isBlank() && output.exitCode == 0) emptyList() else {
                val why = output.stderr.lines().lastOrNull { it.isNotBlank() } ?: output.stdout.lines().firstOrNull { it.isNotBlank() }.orEmpty()
                GoCustomLinters.failed(request.project, name, "No ${run.linter.format.title} report on stdout, exit code ${output.exitCode}: ${why.take(300)}")
                return null
            }
            GoPluginLog.info("lint", "custom linter $name in ${run.workDirectory}: exit code ${output.exitCode}, ${all.size} issues")
            GoLintPlacement.ofFile(all, run.workDirectory, FileUtil.toSystemDependentName(request.file.path))
        } catch (e: Exception) {
            GoCustomLinters.failed(request.project, name, "Cannot run ${run.arguments.first()}: ${GoPluginLog.describe(e)}")
            null
        }
    }

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val document: Document = file.viewProvider.document ?: return
        val virtualFile = file.virtualFile ?: return
        val placed = GoLintPlacement.place(virtualFile, document, result.findings, MARKERS, { it.issue.line }, { it.issue.column })
        for ((range, finding) in placed) {
            if (GoLintDuplicates.reportedNatively(file, range.startOffset, finding.key, finding.issue.text, range.endOffset)) continue
            val line = document.getLineNumber(range.startOffset)
            var annotation = holder.newAnnotation(if (finding.issue.isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING, finding.message).range(range)
            if (finding.key == "errcheck") annotation = annotation.withFix(GoErrcheckFix(handle = true, line)).withFix(GoErrcheckFix(handle = false, line))
            if (finding.key != "typecheck") annotation = annotation.withFix(GoNolintFix(finding.key, line))
            annotation.create()
        }
    }

    private companion object {
        /** Linter (its whole row) -> the stamp of the file it was run on and what it found there. */
        val RESULTS: Key<ConcurrentHashMap<String, Pair<Long, List<GoLintIssue>>>> = Key.create("io.github.golangsupport.lint.custom.results")
        val MARKERS: Key<List<Pair<RangeMarker, GoLinterFinding>>> = Key.create("io.github.golangsupport.lint.custom.markers")
    }
}

/**
 * A save of a Go file: marks it for the linters that run on save, and re-runs the highlighting of the file afterwards. Saving does not
 * change the document, so without the restart the linters would wait for the next edit or for the file to be reopened.
 */
class GoLintSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document)?.takeIf { it.extension == "go" } ?: return
        val settings = GoSettings.getInstance()
        if (!settings.golangciLint && settings.customLinters.none { it.isRunnable }) return
        file.putUserData(SAVED_IN_SESSION, true)
        // later: the file is written after this call
        ApplicationManager.getApplication().invokeLater {
            if (!file.isValid) return@invokeLater
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
            }
        }
    }

    companion object {
        val SAVED_IN_SESSION: Key<Boolean> = Key.create("io.github.golangsupport.lint.savedInSession")
    }
}
