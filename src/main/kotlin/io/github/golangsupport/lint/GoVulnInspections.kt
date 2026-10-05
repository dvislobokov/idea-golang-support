package io.github.golangsupport.lint

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.build.BuildViewCommandOutput
import io.github.golangsupport.cli.CommandOutput
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.mod.GoModFile
import io.github.golangsupport.mod.GoModUpdates
import io.github.golangsupport.mod.GoModule
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import java.io.File

/**
 * The govulncheck inspections read the results [GoVulnService] keeps per module instead of running the tool from an external annotator as
 * golangci-lint does: one run covers the whole module and takes up to minutes, so it cannot follow the highlighting of a file; as
 * inspections the two checks have their ids in the profile (on / off, level, Inspect Code) and need no process while highlighting.
 */
abstract class GoVulnInspectionBase : LocalInspectionTool() {
    protected fun context(file: PsiFile): Pair<GoModule, GoVulnReport>? {
        if (file !is GoFile) return null
        val module = GoModulesService.getInstance(file.project).moduleOf(file.virtualFile ?: file.originalFile.virtualFile) ?: return null
        return GoVulnService.getInstance(file.project).report(module)?.let { module to it }
    }
}

/** `GoVulnerablePackageImport`: an import of a package of a module version with a known vulnerability; the fix upgrades the module to the fixed version. */
class GoVulnerablePackageImportInspection : GoVulnInspectionBase() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val (module, report) = context(file) ?: return null
        val byPackage = report.packages().groupBy { it.pkg }
        if (byPackage.isEmpty()) return null
        return (file as GoFile).imports.flatMap { spec ->
            byPackage[spec.path].orEmpty().map { p ->
                val target: PsiElement = spec.stringLiteral ?: spec
                val fixes = if (!p.isStdlib && p.fixedVersion != null) arrayOf<LocalQuickFix>(GoVulnUpgradeFix(module.root.path, p.module, p.fixedVersion)) else LocalQuickFix.EMPTY_ARRAY
                manager.createProblemDescriptor(target, GoVulnOutput.importMessage(p), isOnTheFly, fixes, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
            }
        }.toTypedArray()
    }
}

/**
 * `GoVulnerableCodeUsages`: a call of the module's code on a trace to a vulnerable function. govulncheck names the call by the position of
 * its parenthesis in the files as they were at the run; the call is looked for on that line by the name of the callee, so a line that has
 * changed since then shows nothing rather than the wrong code.
 */
class GoVulnerableCodeUsagesInspection : GoVulnInspectionBase() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val (module, report) = context(file) ?: return null
        val path = file.virtualFile?.path ?: return null
        val sites = report.callSites(module.path).filter { site ->
            FileUtil.pathsEqual(site.file.replace('\\', '/'), path) || FileUtil.pathsEqual(File(module.root.path, site.file).path.replace('\\', '/'), path)
        }
        if (sites.isEmpty()) return null
        val document = file.viewProvider.document ?: return null
        return sites.mapNotNull { site ->
            val name = calleeAt(file, document, site.line, site.column, site.callee) ?: return@mapNotNull null
            manager.createProblemDescriptor(name, GoVulnOutput.callMessage(site), isOnTheFly, LocalQuickFix.EMPTY_ARRAY, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }.toTypedArray()
    }

    /** The name of the call of [callee] on one-based [line]: first the call at [column] (its parenthesis), then any call of that name on the line. */
    private fun calleeAt(file: PsiFile, document: com.intellij.openapi.editor.Document, line: Int, column: Int, callee: String): PsiElement? {
        if (line - 1 !in 0 until document.lineCount) return null
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        fun nameOf(call: GoCallExpr?): PsiElement? = (call?.expression as? GoReferenceExpression)?.identifier?.takeIf { it.text == callee }
        if (column > 0 && start + column - 1 < end) nameOf(PsiTreeUtil.getParentOfType(file.findElementAt(start + column - 1), GoCallExpr::class.java, false))?.let { return it }
        var offset = start
        while (offset < end) {
            val leaf = file.findElementAt(offset) ?: break
            if (leaf.text == callee) nameOf(PsiTreeUtil.getParentOfType(leaf, GoCallExpr::class.java, false))?.let { return it }
            offset = maxOf(offset + 1, leaf.textRange.endOffset)
        }
        return null
    }
}

/** "Upgrade m to v": `go get m@v` in the module of the file, in the background (then `go mod vendor` when the module vendors). */
class GoVulnUpgradeFix(private val root: String, private val module: String, private val version: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Upgrade vulnerable module"
    override fun getName(): String = "Upgrade $module to $version"
    override fun startInWriteAction(): Boolean = false
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) = GoModUpdates.goGet(project, root, "Go Get $module@$version", listOf("$module@$version"))
}

/**
 * Go | Check Vulnerabilities: govulncheck over every module now (also with the background check off), in the Build window as navigable
 * findings (`file:line:col: …`, the vulnerable modules on their go.mod lines), a balloon with the count; the inspections take the result.
 */
class GoCheckVulnerabilitiesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tool = GoTool.GOVULNCHECK.find() ?: return GoTool.GOVULNCHECK.offerInstallation(project, TITLE) { actionPerformed(e) }
        val roots = GoModulesService.getInstance(project).modules().map { it.root.path }
        if (roots.isEmpty()) return GoCli.notifyInfo(project, TITLE, "No Go module in the project")
        val arguments = GoVulnOutput.arguments(GoSettings.getInstance().tagList()).toTypedArray()
        val commands = GoCli.commandLinesOrNotify(project, TITLE) { roots.map { GoCli.toolCommandLine(tool.path, it, *arguments) } } ?: return
        GoCli.runInBackground(project, TITLE, commands, output = VulnOutput(project), onFailure = { false })
    }

    /** Keeps the JSON away from the Build window: what it shows are the lines of [GoVulnOutput.describe]; stderr (progress, errors) goes there as it is. */
    private class VulnOutput(private val project: Project) : CommandOutput {
        private val build = BuildViewCommandOutput(project, TITLE)
        private val stdout = StringBuilder()
        private var root: String? = null
        private val summaries = ArrayList<GoVulnSummary>()

        override fun commandStarted(command: GeneralCommandLine) {
            root = command.workDirectory?.path
            stdout.setLength(0)
            // what govulncheck finds does not fail the run: warnings, as for vet
            build.started(GoCli.displayString(command), root, isVet = true)
        }

        override fun text(text: String, isError: Boolean) {
            if (isError) build.text(text, true) else stdout.append(text)
        }

        override fun commandFinished(exitCode: Int) {
            val dir = root
            val report = GoVulnOutput.parse(stdout.toString())
            if (report == null || dir == null) {
                build.text(stdout.toString(), false)
            } else {
                val service = GoVulnService.getInstance(project)
                service.store(dir, service.keyOf(dir), report, stdout.toString())
                val goMod = ReadAction.compute<GoModFile?, RuntimeException> {
                    GoModulesService.getInstance(project).modules().firstOrNull { FileUtil.pathsEqual(it.root.path, dir) }?.content
                }
                val lines = GoVulnOutput.describe(report, goMod?.modulePath.orEmpty(), goMod?.requires.orEmpty())
                val summary = report.summary()
                summaries += summary
                build.text((lines + "${summary.text()}.").joinToString("\n", postfix = "\n"), false)
            }
            build.commandFinished(exitCode)
        }

        override fun finished(succeeded: Boolean) {
            build.finished(succeeded)
            if (summaries.isEmpty()) return
            val total = GoVulnSummary(summaries.sumOf { it.called }, summaries.sumOf { it.imported }, summaries.sumOf { it.required })
            val text = total.text() + (if (summaries.size > 1) " in ${summaries.size} modules" else "") + (if (total.total > 0) "; the findings are in the Build window" else "")
            GoCli.notifyInfo(project, TITLE, text)
        }
    }

    private companion object {
        const val TITLE = "Check Vulnerabilities"
    }
}
