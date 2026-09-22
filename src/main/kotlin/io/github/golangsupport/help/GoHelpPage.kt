package io.github.golangsupport.help

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.JBColor
import io.github.golangsupport.cli.GoCli

/**
 * Go | Help Page: what the plugin does and how to reach it, as a page in an editor tab, in the look of the JetBrains sites. The shortcuts
 * are read from the keymap of the IDE, so the page says what the keyboard does here and not what it does elsewhere.
 */
class ShowGoHelpPageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val html = GoHelpPage.html(dark = !JBColor.isBright())
        if (JBCefApp.isSupported()) HTMLEditorProvider.openEditor(project, "Go Help", html)
        else GoCli.notifyError(project, "Go Help", "The page needs the embedded browser (JCEF), which this IDE does not have")
    }
}

object GoHelpPage {
    private class Row(val what: String, val how: String, val note: String = "")

    private fun key(actionId: String, fallback: String = ""): String = KeymapUtil.getFirstKeyboardShortcutText(ActionManager.getInstance().getAction(actionId) ?: return fallback).ifEmpty { fallback }

    fun html(dark: Boolean): String {
        val editing = listOf(
            Row("Complete a statement", key("EditorCompleteStatement", "Ctrl+Shift+Enter"), "Adds the braces of <code>if</code>, <code>for</code>, <code>func</code>, <code>switch</code>, the parentheses of a call, and puts the caret inside"),
            Row("Postfix templates", "<code>.if</code> <code>.else</code> <code>.nil</code> <code>.notnil</code> <code>.err</code> <code>.errv</code> <code>.return</code> <code>.rr</code> <code>.var</code> <code>.for</code> <code>.fori</code> <code>.forr</code> <code>.range</code> <code>.len</code> <code>.print</code> <code>.printf</code> <code>.panic</code> <code>.go</code> <code>.defer</code> <code>.append</code> <code>.not</code> <code>.switch</code> <code>.wrap</code>", "Type an expression, a dot and the key: <code>items.for</code> becomes <code>for _, v := range items {}</code>"),
            Row("Live templates", "<code>err</code> <code>errw</code> <code>errn</code> <code>ife</code> <code>forr</code> <code>fori</code> <code>main</code> <code>meth</code> <code>fn</code> <code>test</code> <code>ttest</code> <code>bench</code> <code>gof</code> <code>deff</code> <code>sel</code> <code>pf</code> <code>ctx</code> <code>str</code> <code>inter</code> <code>enum</code> <code>init</code> <code>hf</code> <code>mu</code> <code>wg</code> <code>mk</code> <code>sw</code>", "Type the name and press Tab; Settings | Editor | Live Templates | Go lists them all"),
            Row("Next idiomatic line", "Tab", "<code>if err != nil { return … }</code> after an assigned error, <code>defer cancel()</code>, <code>defer mu.Unlock()</code>, <code>defer f.Close()</code> appear as grey text"),
            Row("Doc comment", "<code>//</code> above a declaration", "Becomes <code>// Name </code> for the declaration below; off in Settings | Tools | Go, Editor"),
            Row("Auto-import", "Completion", "A name of a package that is not imported yet (<code>strings.ToUpper</code>) adds its import"),
            Row("Reformat", key("ReformatCode", "Ctrl+Alt+L"), "gofmt or goimports; on save by default"),
            Row("Optimize imports", key("OptimizeImports", "Ctrl+Alt+O"), "Through gopls"),
        )
        val generate = listOf(
            Row("Generate", key("Generate", "Alt+Insert"), "Constructor, getters, setters, getters and setters, <code>String()</code>, struct tags, implement interface, test — for the type or function at the caret"),
            Row("Intentions", key("ShowIntentionActions", "Alt+Enter"), "Handle error on a call, add <code>if err != nil</code> after an assignment, add missing return, create a function that is called but not written, struct tags, implement interface, generate test, the refactorings of gopls (extract, inline, fill struct, add test), fixes of golangci-lint"),
            Row("Rename", key("RenameElement", "Shift+F6"), "Through gopls, across the module"),
        )
        val navigation = listOf(
            Row("Go to declaration", key("GotoDeclaration", "Ctrl+B") + " or Ctrl+click", ""),
            Row("Go to implementation", key("GotoImplementation", "Ctrl+Alt+B"), "From a declaration; the <code>I</code> icons in the gutter lead to and from interfaces"),
            Row("Find usages", key("FindUsages", "Alt+F7"), "From a declaration; the counts above declarations are clickable"),
            Row("Go to class / symbol", key("GotoClass", "Ctrl+N") + " / " + key("GotoSymbol", "Ctrl+Alt+Shift+N"), "Types, functions, methods, fields of the project"),
            Row("Structure", key("FileStructurePopup", "Ctrl+F12"), ""),
            Row("Quick documentation", key("QuickJavaDoc", "Ctrl+Q"), "From gopls"),
            Row("Parameter info", key("ParameterInfo", "Ctrl+P"), ""),
        )
        val running = listOf(
            Row("Run / debug", "▶ in the gutter at <code>func main</code>, tests and benchmarks", "The Go run configuration: <code>go run</code> and <code>go test</code>; Debug builds with delve"),
            Row("Tests", "Tool window <b>Go Tests</b>", "All tests of the project with their last results; run, debug, rerun failed"),
            Row("Profile tests", "Run configuration → Profile", "CPU, memory, block, mutex, execution trace; a notification opens it in <code>go tool pprof</code> / <code>trace</code>"),
            Row("Monitor", "Tool window <b>Go Monitor</b>", "CPU, memory, heap, GC, threads, scheduler of a running program; tick <i>Collect runtime telemetry</i> in the run configuration; <i>Goroutines</i> takes a snapshot with delve"),
            Row("Debugger", "Breakpoints in the gutter", "Conditions, hit counts, log messages (right-click a breakpoint), panic breakpoints, Evaluate with function calls, Set Value, Attach to Process (Run | Attach to Process, group Go)"),
            Row("Build, vet, generate, modules", "Menu <b>Go</b>", "Build, Vet, Generate, Modules (tidy, download, vendor), New Go Module, Go on This Machine"),
        )
        val tools = listOf(
            Row("gopls", "Menu <b>Go | gopls</b>", "Add Import, Browse Documentation / Assembly / Free Symbols, Toggle Compiler Optimization Details, Check for Dependency Upgrades, Upgrade All, Run govulncheck, Show Statistics, Show Log, Open Debug Pages, Restart"),
            Row("Code lenses", "In <code>go.mod</code> and above <code>//go:generate</code>", "Tidy, vendor, vulncheck, check for upgrades, upgrade; run go generate"),
            Row("golangci-lint", "Findings in the editor after save", "With fixes: handle error, ignore explicitly, <code>//nolint</code>; the config of the project is used"),
            Row("Settings", "Settings | Tools | Go", "Toolchain, language server (with a page for every setting of gopls), debugger, editor, code quality, tools with Install buttons"),
        )
        val bg = if (dark) "#1E1F22" else "#FFFFFF"
        val fg = if (dark) "#DFE1E5" else "#19191C"
        val muted = if (dark) "#A8ADBD" else "#6C707E"
        val card = if (dark) "#2B2D30" else "#F7F8FA"
        val border = if (dark) "#393B40" else "#EBECF0"
        val accent = "#6B57FF"
        val code = if (dark) "#393B40" else "#EBECF0"
        fun section(title: String, rows: List<Row>) = """
            <section>
              <h2>$title</h2>
              <div class="card">
                <table>
                  ${rows.joinToString("") { "<tr><td class=\"what\">${it.what}</td><td class=\"how\">${it.how}</td><td class=\"note\">${it.note}</td></tr>" }}
                </table>
              </div>
            </section>"""
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>Go Help</title>
            <style>
              :root { color-scheme: ${if (dark) "dark" else "light"}; }
              body { margin: 0; padding: 0 0 48px; background: $bg; color: $fg; font-family: "JetBrains Sans", "Inter", "Segoe UI", system-ui, sans-serif; font-size: 14px; line-height: 1.5; }
              header { padding: 40px 48px 28px; background: linear-gradient(135deg, $accent 0%, #FE2857 55%, #FF318C 100%); color: white; }
              header h1 { margin: 0 0 6px; font-size: 32px; font-weight: 700; letter-spacing: -0.01em; }
              header p { margin: 0; opacity: .9; font-size: 16px; }
              main { padding: 8px 48px; max-width: 1100px; }
              h2 { font-size: 18px; font-weight: 600; margin: 32px 0 10px; }
              .card { background: $card; border: 1px solid $border; border-radius: 12px; padding: 4px 16px; }
              table { width: 100%; border-collapse: collapse; }
              td { padding: 10px 8px; vertical-align: top; border-top: 1px solid $border; }
              tr:first-child td { border-top: none; }
              td.what { width: 22%; font-weight: 600; }
              td.how { width: 34%; }
              td.note { color: $muted; }
              code { background: $code; border-radius: 4px; padding: 1px 5px; font-family: "JetBrains Mono", Consolas, monospace; font-size: 12.5px; white-space: nowrap; }
              .tips { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
              .tip { background: $card; border: 1px solid $border; border-radius: 12px; padding: 14px 16px; }
              .tip b { display: block; margin-bottom: 4px; }
              footer { padding: 24px 48px; color: $muted; font-size: 12px; }
            </style></head>
            <body>
            <header><h1>Go Project Support</h1><p>gopls, delve and the go tools inside the IDE: what is where, and which keys do it. The shortcuts are the ones of your keymap.</p></header>
            <main>
              ${section("Editing", editing)}
              ${section("Generate and fix", generate)}
              ${section("Navigation", navigation)}
              ${section("Run, test, debug, monitor", running)}
              ${section("Tools", tools)}
              <section>
                <h2>Tips</h2>
                <div class="tips">
                  <div class="tip"><b>Errors, fast</b>Type the call, then <code>.err</code>: <code>if err := os.Remove(p); err != nil { return err }</code>. After <code>x, err := f()</code> press Tab on the next line for the check with the right return values.</div>
                  <div class="tip"><b>Table tests in one go</b>Alt+Insert → Test on a function writes the table, the loop and the comparison; <code>ttest</code> does it inside a test file.</div>
                  <div class="tip"><b>Struct tags without typing</b>Alt+Enter inside a struct → Add struct tags: json, yaml, xml, db, in snake or camel case, omitempty on request.</div>
                  <div class="tip"><b>A function you have not written yet</b>Call it, Alt+Enter → Create function: the stub with parameters named after the arguments lands at the end of the file.</div>
                  <div class="tip"><b>What is the program doing?</b>Go Monitor with <i>Collect runtime telemetry</i> shows the heap and the collections without a line in the code; <i>Goroutines</i> shows where every goroutine waits.</div>
                  <div class="tip"><b>gopls knows more</b>Alt+Enter → Refactorings and actions of gopls: extract, inline, fill struct, invert if, add test. Go | gopls | Browse Documentation opens the page of the symbol at the caret.</div>
                </div>
              </section>
            </main>
            <footer>Go Project Support. This page is Go | Help Page; the checks and the roadmap are in the repository of the plugin.</footer>
            </body></html>
        """.trimIndent()
    }
}
