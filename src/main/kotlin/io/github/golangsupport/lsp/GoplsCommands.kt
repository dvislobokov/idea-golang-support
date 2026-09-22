package io.github.golangsupport.lsp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.run.GoRunLauncher
import io.github.golangsupport.testing.GoTests
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import java.io.File

/**
 * The commands of gopls (`gopls.*`), as its code lenses and the menu Go | gopls send them. The platform sends a clicked lens as a
 * notification and hears nothing back: no error, no result. Here a command is a request under a progress in the status bar; what fails
 * is shown, what answers with a text (statistics, views) goes to the log window of gopls. Two are not sent at all: the tests of a lens
 * run in the test runner of the plugin, and `go generate` in the Build window, with their output where it is looked for.
 */
object GoplsCommands {
    private const val TIMEOUT_MS = 10 * 60 * 1000

    /** Commands whose result is text worth reading: printed to the log window, which is then shown. */
    private val REPORTING = setOf("gopls.workspace_stats", "gopls.mem_stats", "gopls.views", "gopls.modules", "gopls.packages", "gopls.list_known_packages")

    fun execute(client: LspClient, contextFile: VirtualFile?, command: Command) {
        val project = client.project
        val log = GoplsLogService.getInstance(project)
        when (command.command) {
            "gopls.run_tests" -> {
                val target = GoplsCommandArguments.testsTarget(command.arguments) ?: return log.error("Cannot read the tests of the lens: ${command.arguments}")
                val directory = client.descriptor.findFileByUri(target.uri)?.parent ?: return log.error("No such file: ${target.uri}")
                log.info("${command.title}: ${target.names.joinToString()} in ${directory.path}")
                ApplicationManager.getApplication().invokeLater { GoRunLauncher.runTests(project, directory.path, target.name, GoTests.pattern(target.names), target.benchmark) }
            }
            "gopls.generate" -> {
                val generate = GoplsCommandArguments.generate(command.arguments) ?: return log.error("Cannot read the directory of the lens: ${command.arguments}")
                val directory = client.descriptor.findFileByUri(generate.uri)?.path ?: return log.error("No such directory: ${generate.uri}")
                log.info("${command.title} in $directory" + if (generate.recursive) " and below" else "")
                val commands = GoCli.commandLinesOrNotify(project, "Go Generate") { listOf(GoCli.commandLine(directory, "generate", if (generate.recursive) "./..." else ".")) } ?: return
                GoCli.runInBackground(project, "Go Generate", commands, refresh = listOf(File(directory)))
            }
            else -> send(client, command)
        }
    }

    /** The request, on a pooled thread, under a progress named after the command; the outcome in the log, a failure also in a balloon. */
    fun send(client: LspClient, command: Command, onResult: ((Any?) -> Unit)? = null) {
        val project = client.project
        val log = GoplsLogService.getInstance(project)
        log.info("${command.title} (${command.command}) " + command.arguments.orEmpty().joinToString(" ") { GoplsCommandArguments.json(it)?.toString() ?: it.toString() })
        object : Task.Backgroundable(project, command.title ?: command.command, true) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    client.sendRequestSync<Any?>(TIMEOUT_MS) { it.workspaceService.executeCommand(ExecuteCommandParams(command.command, command.arguments)) }
                } catch (e: Exception) {
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    val message = GoplsCommandArguments.failure(e)
                    log.error("${command.title}: $message")
                    GoCli.notifyError(project, "gopls: ${command.title}", message)
                    return
                }
                if (result != null && command.command in REPORTING) {
                    log.info("${command.title}:\n" + GoplsCommandArguments.pretty(PRINTER.toJson(result)))
                    ApplicationManager.getApplication().invokeLater { ShowGoplsLogAction.show(project) }
                }
                onResult?.invoke(result)
            }
        }.queue()
    }

    fun command(name: String, title: String, vararg arguments: Any): Command = Command(title, name, arguments.toList())

    private val PRINTER: Gson = GsonBuilder().setPrettyPrinting().create()
}

/** The arguments of the commands of gopls, read from the `Command` of a lens (JSON objects) or made for the menu; pure, for the tests. */
object GoplsCommandArguments {
    class TestsTarget(val uri: String, val names: List<String>, val benchmark: Boolean) {
        val name: String get() = names.singleOrNull() ?: "${names.size} ${if (benchmark) "benchmarks" else "tests"}"
    }

    class Generate(val uri: String, val recursive: Boolean)

    /** `{"URI": "file:///.../x_test.go", "Tests": ["TestA"], "Benchmarks": null}`: the tests, or else the benchmarks. */
    fun testsTarget(arguments: List<Any?>?): TestsTarget? {
        val argument = json(arguments?.firstOrNull()) ?: return null
        val uri = argument.string("URI") ?: return null
        val tests = argument.strings("Tests")
        val benchmarks = argument.strings("Benchmarks")
        return when {
            tests.isNotEmpty() -> TestsTarget(uri, tests, benchmark = false)
            benchmarks.isNotEmpty() -> TestsTarget(uri, benchmarks, benchmark = true)
            else -> null
        }
    }

    /** `{"Dir": "file:///...", "Recursive": false}`. */
    fun generate(arguments: List<Any?>?): Generate? {
        val argument = json(arguments?.firstOrNull()) ?: return null
        return Generate(argument.string("Dir") ?: return null, argument.get("Recursive")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
    }

    /** The words of the server out of the exception of lsp4j: `ResponseErrorException: ...`, wrapped in a few layers by the client. */
    fun failure(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is org.eclipse.lsp4j.jsonrpc.ResponseErrorException) return cause.responseError.message
            cause = cause.cause.takeIf { it !== cause }
        }
        return e.message ?: e.javaClass.simpleName
    }

    /**
     * The JSON of an argument: the tree Gson made of it, or the tree of whatever object the client has. A `JsonObject` of another class
     * loader (seen live: the one of a script run inside the IDE) is not `is JsonObject`, and Gson would take it apart by reflection; its
     * text is JSON all the same.
     */
    fun json(argument: Any?): JsonObject? = when {
        argument == null -> null
        argument is JsonObject -> argument
        argument is JsonElement -> null
        argument.javaClass.name == JsonObject::class.java.name -> runCatching { JsonParser.parseString(argument.toString()) as? JsonObject }.getOrNull()
        else -> runCatching { Gson().toJsonTree(argument) as? JsonObject }.getOrNull()
    }

    /** Gson reads every number of an untyped result as a double: `"Files": 738.0` is 738. */
    fun pretty(json: String): String = WHOLE_DOUBLE.replace(json, "$1")

    private val WHOLE_DOUBLE = Regex("""(?<=: )(\d+)\.0(?=[,\n\]}]|$)""")

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.strings(name: String): List<String> = (get(name)?.takeIf { it.isJsonArray }?.asJsonArray).orEmpty().mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    private fun com.google.gson.JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
