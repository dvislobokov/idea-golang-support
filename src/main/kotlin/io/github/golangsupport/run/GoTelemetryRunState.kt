package io.github.golangsupport.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import io.github.golangsupport.monitor.GoTelemetryProcessHandler
import java.io.File
import java.nio.file.Files

/**
 * `go run` with the telemetry of the runtime: `GODEBUG` is read by every Go program that sees it, the go command included, and with
 * `go run` its own collections would land in the charts. So the program is built first (`go build -o`, what `go run` does anyway) and
 * started by itself with the variable, both inside one process handler; the binary is deleted when it exits.
 */
class GoTelemetryRunState(private val configuration: GoRunConfiguration, environment: ExecutionEnvironment) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
        val directory = Files.createTempDirectory("go-run-").toFile()
        val binary = File(directory, File(configuration.packageDirectory()).name.ifEmpty { "main" } + if (SystemInfo.isWindows) ".exe" else "")
        return GoTelemetryProcessHandler(configuration.buildBinaryCommandLine(binary), configuration.binaryCommandLine(binary)) { directory.deleteRecursively() }
            .also { ProcessTerminatedListener.attach(it) }
    }
}
