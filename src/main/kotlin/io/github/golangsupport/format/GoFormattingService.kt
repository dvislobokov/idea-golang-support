package io.github.golangsupport.format

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.golangsupport.ide.formatter.GoCodeStyleSettings
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.settings.GoFormatter
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Reformat Code of a Go file is `gofmt` or `goimports` over the text of the editor: the formatter of Go is not a matter of taste.
 * The platform takes the first service that [canFormat] a file and no other (`FormattingServiceUtil.findService`; `CoreFormattingService`,
 * the one of `lang.formatter`, is the fallback), so a file claimed here is never formatted by the gofmt port of go-psi-ide on top. With
 * [GoFormatter.NATIVE] the file is not claimed and that port formats (MIGRATION.md step 8j); with [GoFormatter.NONE] the language server does.
 */
class GoFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()
    override fun canFormat(file: PsiFile): Boolean = file is GoFile && GoSettings.getInstance().formatter.isExternalTool
    override fun getNotificationGroupId(): String = GoCli.NOTIFICATION_GROUP
    override fun getName(): String = GoSettings.getInstance().formatter.title

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val executable = formatter() ?: run {
            request.onError("Go", "The formatter is not found: ${GoSettings.getInstance().formatter.title}. See Settings | Go | Formatting and Tools.")
            return null
        }
        val directory = request.context.virtualFile?.parent?.path
        val local = request.context.virtualFile?.let { localPrefixes(request.context.project, it) }.orEmpty()
        return object : FormattingTask {
            private var handler: CapturingProcessHandler? = null

            override fun run() {
                try {
                    val commandLine = commandLine(executable, directory, local)
                    val process = CapturingProcessHandler(commandLine).also { handler = it }
                    process.processInput.use { it.write(request.documentText.toByteArray(StandardCharsets.UTF_8)) }
                    val output = process.runProcess(30_000)
                    // the only way gofmt fails is a syntax error, which the editor shows anyway
                    if (output.exitCode == 0) request.onTextReady(output.stdout) else request.onError("Go", errorMessage(output.stderr))
                } catch (e: Exception) {
                    request.onError("Go", e.message.orEmpty())
                }
            }

            override fun cancel(): Boolean {
                handler?.destroyProcess()
                return true
            }

            override fun isRunUnderProgress(): Boolean = true
        }
    }

    companion object {
        /** gofmt lies next to `go`: in `bin` of the toolchain. */
        fun formatter(): File? = when (GoSettings.getInstance().formatter) {
            GoFormatter.GOIMPORTS -> GoTool.GOIMPORTS.find()
            GoFormatter.GOLANGCI_LINT_FMT -> GoTool.GOLANGCI_LINT.find()
            GoFormatter.GOFMT -> listOfNotNull(GoCli.findExecutable()?.let { File(it).parentFile }, GoEnvironment.quick().goRoot?.let { File(it, "bin") })
                .map { File(it, GoCli.executableName("gofmt")) }.firstOrNull { it.isFile }
            GoFormatter.NONE, GoFormatter.NATIVE -> null
        }

        /**
         * The text comes on stdin, the result on stdout, for every formatter; golangci-lint needs to be told so (`fmt --stdin`, v2).
         * goimports gets `-local` with the prefixes of Code Style | Go | Imports ([local]).
         */
        fun arguments(formatter: GoFormatter, local: List<String> = emptyList()): List<String> = when {
            formatter == GoFormatter.GOLANGCI_LINT_FMT -> listOf("fmt", "--stdin")
            formatter == GoFormatter.GOIMPORTS && local.isNotEmpty() -> listOf("-local", local.joinToString(","))
            else -> emptyList()
        }

        /** In the directory of the file: golangci-lint finds its `.golangci.yml` from there, goimports its module. */
        fun commandLine(executable: File, directory: String?, local: List<String> = emptyList()) =
            GoCli.toolCommandLine(executable.path, directory, *arguments(GoSettings.getInstance().formatter, local).toTypedArray())

        /**
         * The local prefixes typed in Code Style | Go | Imports for [file] (with the project group on); empty otherwise, and then goimports
         * keeps its own grouping: the main module is not passed on its own, so that a project that never asked keeps what goimports writes.
         */
        fun localPrefixes(project: Project, file: VirtualFile): List<String> {
            if (GoSettings.getInstance().formatter != GoFormatter.GOIMPORTS) return emptyList()
            return ReadAction.compute<List<String>, RuntimeException> {
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute emptyList()
                val style = GoCodeStyleSettings.of(psi)
                if (style.IMPORT_GROUP_LOCAL) style.localPrefixes() else emptyList()
            }
        }

        /** `<standard input>:12:3: expected ...` -> `12:3: expected ...`, the first of them. */
        fun errorMessage(stderr: String): String = stderr.lineSequence().firstOrNull { it.isNotBlank() }?.removePrefix("<standard input>:")?.trim() ?: "The formatter has failed"
    }
}
