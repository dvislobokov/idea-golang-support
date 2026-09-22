package io.github.golangsupport.format

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.psi.PsiFile
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.settings.GoFormatter
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Reformat Code of a Go file is `gofmt` or `goimports` over the text of the editor: the formatter of Go is not a matter of taste. */
class GoFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()
    override fun canFormat(file: PsiFile): Boolean = file is GoFile && GoSettings.getInstance().formatter != GoFormatter.NONE
    override fun getNotificationGroupId(): String = GoCli.NOTIFICATION_GROUP
    override fun getName(): String = GoSettings.getInstance().formatter.title

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val executable = formatter() ?: run {
            request.onError("Go", "The formatter is not found: ${GoSettings.getInstance().formatter.title}. See Settings | Tools | Go.")
            return null
        }
        val directory = request.context.virtualFile?.parent?.path
        return object : FormattingTask {
            private var handler: CapturingProcessHandler? = null

            override fun run() {
                try {
                    val commandLine = GoCli.toolCommandLine(executable.path, directory)
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
            GoFormatter.GOFMT -> listOfNotNull(GoCli.findExecutable()?.let { File(it).parentFile }, GoEnvironment.quick().goRoot?.let { File(it, "bin") })
                .map { File(it, GoCli.executableName("gofmt")) }.firstOrNull { it.isFile }
            GoFormatter.NONE -> null
        }

        /** `<standard input>:12:3: expected ...` -> `12:3: expected ...`, the first of them. */
        fun errorMessage(stderr: String): String = stderr.lineSequence().firstOrNull { it.isNotBlank() }?.removePrefix("<standard input>:")?.trim() ?: "The formatter has failed"
    }
}
