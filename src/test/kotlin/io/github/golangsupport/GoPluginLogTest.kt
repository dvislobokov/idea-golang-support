package io.github.golangsupport

import com.intellij.execution.ExecutionException
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.cli.DailyLog
import io.github.golangsupport.cli.GoLogs
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.cli.GoPluginLogsToolWindowFactory
import io.github.golangsupport.cli.LogEntry
import io.github.golangsupport.cli.LogLevel
import io.github.golangsupport.debugger.GoDebuggerLogs
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The journal of the plugin: one readable line per event, without stack traces for the failures of external programs, in a file the
 * user can send and in the Go Plugin Logs tool window.
 */
class GoPluginLogTest : BasePlatformTestCase() {
    fun testLineFormat() {
        val time = LocalDateTime.of(2026, 10, 2, 11, 22, 49, 613_000_000)
        assertEquals("11:22:49.613 WARN  [gopls] Stopped unexpectedly", GoPluginLog.line(time, LogLevel.WARN, "gopls", "Stopped unexpectedly"))
        // the lines of a multi-line text stay under the first one, so that grep by the category and the eye both find them
        assertEquals("11:22:49.613 ERROR [go] first\n" + " ".repeat(24) + "second", GoPluginLog.line(time, LogLevel.ERROR, "go", "first\nsecond"))
        assertEquals("11:22:49.613 [Build] > go build ./...", GoLogs.line(LocalTime.of(11, 22, 49, 613_000_000), "Build", "> go build ./..."))
    }

    fun testExpectedFailuresAreTheirMessageOnly() {
        assertEquals("Cannot run program \"go\": error=2", GoPluginLog.describe(ExecutionException("Cannot run program \"go\": error=2")))
        assertEquals("Stream closed", GoPluginLog.describe(java.io.IOException("Stream closed")))
        // a wrapped expected failure is still expected, and the cause adds what the wrapper does not say
        assertEquals("build failed: Stream closed", GoPluginLog.describe(RuntimeException("build failed", java.io.IOException("Stream closed"))))
        assertTrue(GoPluginLog.isExpected(java.util.concurrent.ExecutionException(java.io.IOException("Stream closed"))))
    }

    fun testUnexpectedFailuresNameTheClassAndTheFrameOfThePlugin() {
        val described = GoPluginLog.describe(IllegalStateException("no client"))
        assertTrue(described, described.startsWith("IllegalStateException: no client at GoPluginLogTest."))
        assertEquals("one frame, not a trace", 1, described.lines().size)
        assertEquals("NullPointerException", GoPluginLog.describe(NullPointerException()).substringBefore(" at "))
    }

    fun testTheJournalIsWrittenAndReplayed() {
        GoPluginLog.warn("test", "something failed", ExecutionException("exit code 2"))
        GoPluginLog.info("test", "two\nlines")
        val file = GoLogs.pluginDirectory.resolve("plugin-${DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now())}.log")
        val text = Files.readString(file)
        assertTrue(text, text.lines().any { it.matches(Regex("""\d\d:\d\d:\d\d\.\d{3} WARN  \[test] something failed: exit code 2""")) })
        assertTrue(text, text.contains("[test] two\n") && text.contains(" ".repeat(26) + "lines\n"))

        val seen = ArrayList<LogEntry>()
        val disposable = Disposer.newDisposable()
        try {
            GoPluginLog.subscribe(disposable) { seen += it }
            assertTrue("what was logged before is replayed", seen.any { it.category == "test" && it.text == "two\nlines" })
            val before = seen.size
            GoPluginLog.error("test", "live")
            assertEquals(before + 1, seen.size)
            assertEquals(LogLevel.ERROR, seen.last().level)
        } finally {
            Disposer.dispose(disposable)
        }
        val after = seen.size
        GoPluginLog.info("test", "after dispose")
        assertEquals("a disposed subscriber hears nothing", after, seen.size)
        assertEquals("plugin-20260901.log", DailyLog("plugin", "plugin-").outdated(listOf("plugin-20260901.log", "plugin-20260920.log", "commands-20260901.log"), LocalDate.of(2026, 9, 22)).single())
    }

    fun testCommandResultsGoToTheJournalAndTheCommandLog() {
        GoLogs.commandFinished("Build", "exit code 1 in 2.0 s", failed = true, lastLines = "./main.go:5:2: undefined: x")
        val entry = GoPluginLog.entries.last { it.category == GoLogs.CATEGORY_COMMANDS }
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("Build: exit code 1 in 2.0 s\n./main.go:5:2: undefined: x", entry.text)
        val commands = Files.readString(GoLogs.commandsDirectory.resolve(GoLogs.commandLogName(LocalDate.now())))
        assertTrue(commands, commands.contains("[Build] exit code 1 in 2.0 s\n") && commands.contains("[Build] ./main.go:5:2: undefined: x\n"))
        assertEquals("exit code 0 in 0.0 s", GoLogs.result(0, System.currentTimeMillis()))
        assertTrue(GoLogs.result(-1, System.currentTimeMillis(), cancelled = true).startsWith("cancelled after"))
        assertEquals("c\nd", GoLogs.tail("a\nb\nc\nd", "", lines = 2))
        assertEquals("err", GoLogs.tail("out", "err"))
    }

    fun testOneFolderForEveryLog() {
        assertEquals(GoLogs.root.resolve("delve"), GoDebuggerLogs.directory)
        assertEquals(GoLogs.root.resolve("delve").resolve("protocol"), GoDebuggerLogs.protocolDirectory)
    }

    fun testMenuAndToolWindow() {
        val actions = ActionManager.getInstance()
        val menu = (actions.getAction("Go.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        val logs = (actions.getAction("Go.Logs") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertTrue(menu.toString(), "Go.Logs" in menu && menu.indexOf("Go.Logs") < menu.indexOf("Go.HelpPage"))
        assertEquals(listOf("Go.PluginLogs", "Go.OpenLogsFolder"), logs.filterNotNull())
        assertEquals("Plugin Logs", actions.getAction("Go.PluginLogs").templatePresentation.text)
        assertEquals("Open Logs Folder", actions.getAction("Go.OpenLogsFolder").templatePresentation.text)
        // not on the stripe until asked for: a log is for the day something does not work
        assertFalse(GoPluginLogsToolWindowFactory().shouldBeAvailable(project))
        GoPluginLogsToolWindowFactory.show(project)
    }
}
