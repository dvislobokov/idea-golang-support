"""End-to-end scenario of go-psi's IDE features in a real sandbox IDE, driven through the Remote Robot (robot.py).

    python tools/ui-robot/autotest.py                 start `./gradlew :plugin:runIdeForUiTests`, run every step, exit the IDE
    python tools/ui-robot/autotest.py --attach        use the sandbox that already runs on ROBOT_PORT (default 8084), keep it running
    python tools/ui-robot/autotest.py --steps 3,4,5   only these steps (1 = open the project, run it once per IDE session)
    python tools/ui-robot/autotest.py --perf          performance mode (perf.py): steps 1, 2, P1-P9, 15; no functional steps
    python tools/ui-robot/autotest.py --perf --scenario   performance mode first, then every functional step
    python tools/ui-robot/autotest.py --perf --cold   delete the sandbox's index and caches before the start (cold first open)
    python tools/ui-robot/autotest.py --help          this text

Options combine (`--attach --perf --steps 1`); an unknown option prints this text and runs nothing.

Writes build/ui-robot/report.md and build/ui-robot/NN-step.png (pictures of the IDE frame or of a popup, painted by the component,
never of the desktop). Each step is PASS or FAIL with the text read from the IDE as evidence. The performance mode also
writes build/ui-robot/perf.md and perf.json (see perf.py and docs/TESTING.md "Performance mode").

The scenario project (tools/ui-robot/project) is copied to %TEMP%/gopsi-ui-project and opened from there: outside the repository,
so that the user's main IDE does not analyse it, and with its own go.mod. Every step that edits a file first restores its text.
"""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import perf
import robot

REPO = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(REPO, "build", "ui-robot")
SOURCE_PROJECT = os.path.join(HERE, "project")
SCRATCH = os.path.join(tempfile.gettempdir(), "gopsi-ui-project")
# The sandbox of this repository (idea-golang-support, IDEA 2026.1.4); GOPSI_SANDBOX_LOG overrides it (the go-psi layout was sandbox/plugin/IU-2026.1.5).
SANDBOX_LOG = os.environ.get("GOPSI_SANDBOX_LOG") or os.path.join(REPO, ".intellijPlatform", "sandbox", "idea-golang-support", "IU-2026.1.4", "log_runIdeForUiTests", "idea.log")
GOROOT = os.environ.get("GOROOT") or r"C:\Program Files\Go"
GOFMT = os.path.join(GOROOT, "bin", "gofmt.exe")
PLUGIN_LINE = "Go PSI (0.0.9)"

# ------------------------------------------------------------------------------------------------------------------------------
# JavaScript helpers, put in front of every step script (after robot's prelude.js). Rhino: `var` only (a `const` in a loop keeps
# its first value), and everything that touches Swing or the editor runs on the EDT through edt()/later().

HEADER = r"""
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.project.DumbService)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.editor.ScrollType)
importClass(com.intellij.openapi.editor.impl.DocumentMarkupModel)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.wm.IdeFocusManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.daemon.DaemonCodeAnalyzer)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.codeInsight.daemon.impl.HighlightInfo)
importClass(com.intellij.lang.annotation.HighlightSeverity)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var ROOT = __ROOT__
var openProjects = ProjectManager.getInstance().getOpenProjects()
var project = openProjects.length > 0 ? openProjects[openProjects.length - 1] : null
function now() { return java.lang.System.currentTimeMillis() }
function sleep(ms) { java.lang.Thread.sleep(ms) }
// NON_MODAL by default: model changes under ModalityState.any() are write-unsafe (TransactionGuard); edtAny() only reads or clicks
function edt(fn, seconds, modality) {
    var f = new CompletableFuture()
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        try { f.complete([true, fn()]) } catch (e) { f.complete([false, String(e)]) }
    } }), modality || ModalityState.nonModal())
    var r = f.get(seconds || 120, TimeUnit.SECONDS)
    if (!r[0]) throw new java.lang.RuntimeException("on the EDT: " + r[1])
    return r[1]
}
// for whatever may open a modal dialog: invokeLater returns at once, the caller polls
function later(fn) {
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        try { fn() } catch (e) { java.lang.System.err.println("robot later: " + e) }
    } }), ModalityState.nonModal())
}
function edtAny(fn, seconds) { return edt(fn, seconds, ModalityState.any()) }
function read(fn) { return ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: fn })) }
function path(rel) { return rel.indexOf(":") > 0 || rel.charAt(0) == "/" ? rel : ROOT + "/" + rel }
function vf(rel) {
    var f = LocalFileSystem.getInstance().refreshAndFindFileByPath(path(rel))
    if (f == null) throw new java.lang.IllegalArgumentException("no file " + path(rel))
    return f
}
function openFile(rel, line) {
    var f = vf(rel)
    return edt(function () { return FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, f, line || 0, 0), true) })
}
function selected() { return edt(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() }) }
// String(...) around read(): what crosses read()/edt() is a java.lang.String, whose replace() is ambiguous for Rhino
function fileName(e) { return String(read(function () { var f = FileDocumentManager.getInstance().getFile(e.getDocument()); return f == null ? "?" : f.getPath() })) }
function textOf(e) { return String(read(function () { return e.getDocument().getText() })) }
function psiOf(e) { return read(function () { return PsiDocumentManager.getInstance(project).getPsiFile(e.getDocument()) }) }
function lineAt(e, offset) { return read(function () { return e.getDocument().getLineNumber(offset) + 1 }) }
function lineText(e, line) { return String(read(function () { var d = e.getDocument(); return d.getText().substring(d.getLineStartOffset(line - 1), d.getLineEndOffset(line - 1)) })) }
function caretLine(e) { return read(function () { return e.getDocument().getLineNumber(e.getCaretModel().getOffset()) + 1 }) }
function focus(e) { IdeFocusManager.getInstance(project).requestFocus(e.getContentComponent(), true) }
function caretTo(e, needle, delta) {
    return edt(function () {
        var at = String(e.getDocument().getText()).indexOf(needle)
        if (at < 0) throw new java.lang.IllegalArgumentException("not in the text: " + needle)
        e.getCaretModel().moveToOffset(at + (delta || 0))
        e.getScrollingModel().scrollToCaret(ScrollType.CENTER)
        focus(e)
        return at + (delta || 0)
    })
}
function setText(e, text) {
    edt(function () {
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { e.getDocument().setText(text) } }))
        PsiDocumentManager.getInstance(project).commitDocument(e.getDocument())
        FileDocumentManager.getInstance().saveDocument(e.getDocument())
        return true
    })
}
function smart() { return edt(function () { return !DumbService.getInstance(project).isDumb() }) }
// milliseconds until the daemon has finished every pass on the file of `e` (-1 on timeout)
function waitDaemon(e, timeout) {
    var start = now()
    sleep(150)
    while (now() - start < (timeout || 90000)) {
        var done = edt(function () {
            var pdm = PsiDocumentManager.getInstance(project)
            pdm.commitAllDocuments()
            if (DumbService.getInstance(project).isDumb()) return false
            var psi = pdm.getPsiFile(e.getDocument())
            var d = DaemonCodeAnalyzer.getInstance(project)
            return !d.isRunningOrPending() && d.isAllAnalysisFinished(psi)
        })
        if (done) return now() - start
        sleep(50)
    }
    return -1
}
function infos(e, min) {
    return read(function () {
        var out = []
        var hs = DocumentMarkupModel.forDocument(e.getDocument(), project, true).getAllHighlighters()
        for (var i = 0; i < hs.length; i++) {
            var info = HighlightInfo.fromRangeHighlighter(hs[i])
            if (info == null) continue
            if (min != null && info.getSeverity().compareTo(min) < 0) continue
            out.push(info)
        }
        out.sort(function (a, b) { return a.getStartOffset() - b.getStartOffset() })
        return out
    })
}
function describeInfo(e, info) {
    return info.getSeverity() + " line " + lineAt(e, info.getStartOffset()) + " '" + info.getText() + "': " + info.getDescription()
}
function problems(e) {
    var list = infos(e, HighlightSeverity.WEAK_WARNING)
    var out = []
    for (var i = 0; i < list.length; i++) out.push(describeInfo(e, list[i]))
    return out
}
function invoke(id, e) {
    later(function () {
        var a = ActionManager.getInstance().getAction(id)
        if (a == null) throw new java.lang.IllegalArgumentException("no action " + id)
        if (e != null) focus(e)
        ActionManager.getInstance().tryToExecute(a, null, e == null ? null : e.getContentComponent(), "robot", true)
    })
}
// texts of the visible popups and dialogs (not the frame): lists, labels, trees, tables, editor panes
function popupTexts() {
    return edtAny(function () {
        var out = ""
        function texts(c, depth) {
            var t = ""
            if (c instanceof javax.swing.JList) { var m = c.getModel(); for (var i = 0; i < m.getSize(); i++) t += "  row: " + m.getElementAt(i) + "\n" }
            if (c instanceof javax.swing.JTable) { for (var r = 0; r < c.getRowCount(); r++) { var row = ""; for (var k = 0; k < c.getColumnCount(); k++) row += c.getValueAt(r, k) + " | "; t += "  table: " + row + "\n" } }
            if (c instanceof javax.swing.JLabel && c.getText() != null && String(c.getText()).length > 0) t += "  label: " + c.getText() + "\n"
            if (c instanceof javax.swing.text.JTextComponent && c.isShowing()) { var s = String(c.getText()); if (s.length > 0) t += "  text: " + s.replace(/\s+/g, " ").substring(0, 1500) + "\n" }
            if (c instanceof javax.swing.JTree) { for (var i = 0; i < c.getRowCount(); i++) t += "  tree: " + c.getPathForRow(i).getLastPathComponent() + "\n" }
            if (c instanceof javax.swing.AbstractButton && c.getText() != null && String(c.getText()).length > 0) t += "  button: " + c.getText() + "\n"
            if (c instanceof java.awt.Container && depth < 30) { var kids = c.getComponents(); for (var i = 0; i < kids.length; i++) t += texts(kids[i], depth + 1) }
            return t
        }
        var windows = java.awt.Window.getWindows()
        for (var w = 0; w < windows.length; w++) {
            var win = windows[w]
            if (!win.isShowing() || win instanceof javax.swing.JFrame) continue
            out += win.getClass().getSimpleName() + (win instanceof java.awt.Dialog ? " '" + win.getTitle() + "'" : "") + "\n" + texts(win, 0)
        }
        // lightweight popups live in the layered pane of the frame
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            var layered = frame.getLayeredPane().getComponentsInLayer(javax.swing.JLayeredPane.POPUP_LAYER)
            for (var i = 0; i < layered.length; i++) if (layered[i].isShowing()) out += "popup-layer " + layered[i].getClass().getSimpleName() + "\n" + texts(layered[i], 0)
        }
        return out
    })
}
function escapeAll() {
    edtAny(function () {
        var windows = java.awt.Window.getWindows()
        for (var w = 0; w < windows.length; w++) if (windows[w].isShowing() && !(windows[w] instanceof javax.swing.JFrame)) {
            var f = windows[w].getMostRecentFocusOwner() || windows[w]
            f.dispatchEvent(new java.awt.event.KeyEvent(f, java.awt.event.KeyEvent.KEY_PRESSED, now(), 0, java.awt.event.KeyEvent.VK_ESCAPE, "\u001b"))
        }
        var e = FileEditorManager.getInstance(project).getSelectedTextEditor()
        if (e != null) {
            var lookup = com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(e)
            if (lookup != null) lookup.hideLookup(true)
            com.intellij.codeInsight.hint.HintManager.getInstance().hideAllHints()
        }
        return true
    })
}
function clickButton(text) {
    var button = edtAny(function () {
        var found = null
        function walk(c) {
            if (found != null) return
            if (c instanceof javax.swing.AbstractButton && c.isShowing() && String(c.getText() || "").replace(/<[^>]*>/g, "").indexOf(text) >= 0) { found = c; return }
            if (c instanceof java.awt.Container) { var kids = c.getComponents(); for (var i = 0; i < kids.length; i++) walk(kids[i]) }
        }
        var windows = java.awt.Window.getWindows()
        for (var w = 0; w < windows.length; w++) if (windows[w].isShowing() && windows[w] instanceof java.awt.Dialog) walk(windows[w])
        return found
    })
    if (button == null) return "no button " + text
    // the click runs in the dialog's modality: under any() a Refactor button would change the model write-unsafely
    // (TransactionGuard logs it, blaming whichever plugin is on the stack)
    edt(function () { button.doClick(); return true }, 120, ModalityState.stateForComponent(button))
    return "clicked " + text
}
"""


def js(body, timeout=300, **values):
    """Runs prelude.js + HEADER + body; each __KEY__ of the body is replaced by the JSON literal of the value."""
    with open(os.path.join(robot.SCRIPTS, "prelude.js"), encoding="utf-8") as f:
        prelude = f.read()
    header = HEADER.replace("__ROOT__", json.dumps(SCRATCH.replace("\\", "/")))
    for key, value in values.items():
        body = body.replace("__%s__" % key, json.dumps(value))
    # the value of the last statement, always as a java.lang.String: a Rhino ConsString (`"a" + b`) sent back as it is makes the
    # server's JSON serializer recurse until it fails with HTTP 500
    # server's JSON serializer recurse until it fails with HTTP 500 (and so does a thrown exception: caught here and passed as text).
    # eval() runs our own scenario script, never outside input.
    wrapped = "var __r; try { __r = 'OK:' + String(eval(%s)) } catch (__e) { __r = 'SCRIPT ERROR: ' + __e + (__e.rhinoException ? '' : '') }\nnew java.lang.String(__r)" % json.dumps(body)
    result = robot.js(prelude + "\n" + header + "\n" + wrapped, timeout=timeout)
    if result is None or not result.startswith("OK:"):
        raise robot.RobotError("robot: %s" % result)
    return result[3:]


# ------------------------------------------------------------------------------------------------------------------------------
# Report

RESULTS = []


def shot(number, name, xpath=None):
    path = os.path.join(OUT, "%02d-%s.png" % (number, name))
    try:
        robot.shot(path, xpath)
        return os.path.basename(path)
    except Exception as e:  # noqa: BLE001 - a popup may have closed already; the frame is still worth a picture
        if xpath:
            return shot(number, name)
        return "no screenshot (%s)" % str(e).splitlines()[0]


def record(number, title, checks, evidence, pictures):
    """checks: list of (description, ok)."""
    ok = all(c[1] for c in checks)
    RESULTS.append({"n": number, "title": title, "ok": ok, "checks": checks, "evidence": evidence, "pictures": pictures})
    print("%-4s step %d %s" % ("PASS" if ok else "FAIL", number, title))
    for description, passed in checks:
        if not passed:
            print("       failed: " + description)
    write_report()


def write_report():
    os.makedirs(OUT, exist_ok=True)
    lines = ["# go-psi UI robot report", "",
             "Sandbox: `:plugin:runIdeForUiTests` (IntelliJ IDEA 2026.1.5, robot-server on %s), project `%s`, %s." % (
                 robot.BASE, SCRATCH, time.strftime("%Y-%m-%d %H:%M")), "",
             "| # | Step | Result | Checks | Screenshot |", "|---|---|---|---|---|"]
    for r in RESULTS:
        failed = [c[0] for c in r["checks"] if not c[1]]
        summary = ("failed: " + "; ".join(failed)) if failed else "; ".join(c[0] for c in r["checks"])
        lines.append("| %d | %s | %s | %s | %s |" % (r["n"], r["title"], "PASS" if r["ok"] else "**FAIL**",
                                                   summary.replace("|", "\\|").replace("\n", " "), ", ".join(r["pictures"])))
    lines += ["", "## Evidence", ""]
    for r in RESULTS:
        lines += ["### %d. %s: %s" % (r["n"], r["title"], "PASS" if r["ok"] else "FAIL"), ""]
        for description, passed in r["checks"]:
            lines.append("- [%s] %s" % ("x" if passed else " ", description))
        lines += ["", "```", r["evidence"].rstrip(), "```", ""]
    with open(os.path.join(OUT, "report.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def source(rel):
    with open(os.path.join(SOURCE_PROJECT, rel), encoding="utf-8") as f:
        return f.read()


def write_scratch(rel, text):
    """Creates a file of the scratch project once; later runs set the text through the editor (writing a file that is open in the
    IDE behind its back raises the modal 'File Cache Conflict' dialog, which blocks everything)."""
    path = os.path.join(SCRATCH, rel)
    if os.path.exists(path):
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


# ------------------------------------------------------------------------------------------------------------------------------
# Log checks

def log_text():
    with open(SANDBOX_LOG, encoding="utf-8", errors="replace") as f:
        text = f.read()
    marker = text.rfind("IDE STARTED")
    return text[text.rfind("\n", 0, marker) + 1:] if marker >= 0 else text


def gopsi_exceptions():
    """ERROR records of the current IDE session whose text or stack trace mentions go-psi."""
    found = []
    record_lines = []
    for line in log_text().splitlines() + ["2000-01-01 00:00:00,000 [0] INFO - end"]:
        if re.match(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d+ ", line):
            if record_lines and (" ERROR - " in record_lines[0] or " SEVERE - " in record_lines[0]) and any("gopsi" in l for l in record_lines):
                found.append("\n".join(record_lines[:25]))
            record_lines = [line]
        else:
            record_lines.append(line)
    return found


# ------------------------------------------------------------------------------------------------------------------------------
# Steps

GRADLE = None
MEASURE_OPEN = False  # --perf: step 1 opens the project through perf.measure_open() (P7 first open)
FIRST_OPEN = None


def port_open():
    try:
        with socket.create_connection(("127.0.0.1", int(robot.PORT)), timeout=2):
            return True
    except OSError:
        return False


def wipe_sandbox_indexes():
    """--cold: the sandbox's index and caches (VFS) go, so the first open indexes everything again. Only this checkout's
    runIdeForUiTests sandbox is touched."""
    system = os.path.join(os.path.dirname(os.path.dirname(SANDBOX_LOG)), "system_runIdeForUiTests")
    removed = []
    for name in ("index", "caches"):
        path = os.path.join(system, name)
        if os.path.isdir(path):
            shutil.rmtree(path)
            removed.append(path)
    return removed


def step1_start(attach, cold=False):
    global GRADLE
    evidence = []
    if not attach:
        if port_open():
            raise SystemExit("autotest: something listens on %s already; stop it or use --attach" % robot.PORT)
        if cold:
            evidence.append("--cold: removed %s" % wipe_sandbox_indexes())
        log = open(os.path.join(OUT, "gradle-runIdeForUiTests.log"), "w", encoding="utf-8")  # noqa: SIM115 - gradle writes to it until the IDE exits
        gradlew = os.path.join(REPO, "gradlew.bat" if os.name == "nt" else "gradlew")
        GRADLE = subprocess.Popen([gradlew, ":plugin:runIdeForUiTests", "-ProbotPort=" + robot.PORT, "--console=plain"],
                                  cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        evidence.append("started gradle pid %d" % GRADLE.pid)
    started = time.time()
    up = robot.wait_up(tries=450)
    evidence.append("robot answered after %.0f s: %s" % (time.time() - started, up))
    if not up:
        record(1, "Start the sandbox, open the project, smart mode", [("robot-server answers", False)], "\n".join(evidence), [])
        raise SystemExit("autotest: the robot never answered")
    # a fresh scratch copy of the scenario project
    already = js("var r = ''; for (var i = 0; i < openProjects.length; i++) r += openProjects[i].getBasePath() + '\\n'; r") or ""
    if SCRATCH.replace("\\", "/") not in already:
        if os.path.isdir(SCRATCH):
            shutil.rmtree(SCRATCH)
        shutil.copytree(SOURCE_PROJECT, SCRATCH)
        dismiss_startup_dialogs(evidence)
        if MEASURE_OPEN:
            global FIRST_OPEN
            FIRST_OPEN = perf.measure_open(sys.modules[__name__], "first-%d" % int(time.time()))
            evidence.append("opened %s: smart after %d ms, GOROOT indexed after %d ms" % (SCRATCH, FIRST_OPEN["firstSmartMs"], FIRST_OPEN["gorootIndexedMs"]))
        else:
            robot.open_project(SCRATCH)
            evidence.append("opening " + SCRATCH)
    else:
        evidence.append("already open: " + SCRATCH + (" (reopened by the IDE at startup or --attach: no first-open measurement)" if MEASURE_OPEN else ""))
    deadline = time.time() + 300
    state = ""
    while time.time() < deadline:
        try:
            state = js("""
                if (project == null || String(project.getBasePath()) != ROOT) 'not open'
                else if (!project.isInitialized()) 'initializing'
                else if (DumbService.getInstance(project).isDumb()) 'dumb'
                else 'smart ' + project.getBasePath()
            """)
        except robot.RobotError as e:
            state = str(e).splitlines()[0]
        if state.startswith("smart"):
            break
        time.sleep(2)
    evidence.append("project state: " + state)
    dismiss_startup_dialogs(evidence)
    js("""
        var closed = 0
        var others = []
        for (var i = 0; i < openProjects.length; i++) if (String(openProjects[i].getBasePath()) != ROOT) others.push(openProjects[i])
        later(function () { for (var i = 0; i < others.length; i++) ProjectManager.getInstance().closeAndDispose(others[i]) })
        closed = others.length
        // no tips, no exit confirmation: the end of the scenario exits the IDE
        com.intellij.ide.GeneralSettings.getInstance().setConfirmExit(false)
        // the next start shows the welcome screen: step 1 opens the project itself (and --perf measures that open)
        com.intellij.ide.GeneralSettings.getInstance().setReopenLastProject(false)
        closed
    """)
    time.sleep(3)
    pictures = [shot(1, "project-open")]
    record(1, "Start the sandbox, open the project, smart mode",
           [("robot-server answers on %s" % robot.BASE, up), ("project %s open and in smart mode" % SCRATCH, state.startswith("smart"))],
           "\n".join(evidence), pictures)


def dismiss_startup_dialogs(evidence):
    """A fresh sandbox greets with a modal dialog ("Meet the Islands Theme"), and a modal dialog blocks every non-modal EDT call."""
    for _ in range(3):
        texts = js("popupTexts()")
        if "Dialog '" not in texts:
            return
        if "Islands" in texts or "Quick Tour" in texts:
            evidence.append("startup dialog: " + js('clickButton("Skip")'))
        else:
            evidence.append("left open: " + texts.replace("\n", " | ")[:200])
            return
        time.sleep(1)


def step2_log(final=False):
    text = log_text()
    loaded = [l for l in text.splitlines() if "Loaded custom plugins" in l]
    errors = gopsi_exceptions()
    all_errors = [l for l in text.splitlines() if " ERROR - " in l or " SEVERE - " in l]
    evidence = "\n".join(loaded) + "\n\nERROR records this session: %d\n%s\n\ngo-psi exceptions: %d\n%s" % (
        len(all_errors), "\n".join(all_errors[:20]), len(errors), "\n\n".join(errors[:5]))
    number = 16 if final else 2
    title = "idea.log at the end: no go-psi exceptions" if final else "idea.log: plugin loaded, no go-psi exceptions"
    checks = [("no exceptions from io.github.golangsupport (%d found)" % len(errors), not errors)]
    if not final:
        checks.insert(0, ("'%s' in the loaded plugins" % PLUGIN_LINE, any(PLUGIN_LINE in l for l in loaded)))
    record(number, title, checks, evidence, [])


def step3_valid():
    out = json.loads(js("""
        var e = openFile("main.go")
        setText(e, __TEXT__)
        var ms = waitDaemon(e)
        var all = infos(e, null)
        var keys = {}
        for (var i = 0; i < all.length; i++) {
            var k = all[i].forcedTextAttributesKey
            if (k != null && String(k.getExternalName()).indexOf("GO_") == 0) keys[String(k.getExternalName())] = (keys[String(k.getExternalName())] || 0) + 1
        }
        var errors = 0, list = problems(e)
        for (var i = 0; i < list.length; i++) if (list[i].indexOf("ERROR") == 0) errors++
        JSON.stringify({ ms: ms, problems: list, errors: errors, keys: keys })
    """, TEXT=source("main.go")))
    pictures = [shot(3, "valid-file")]
    keys = out["keys"]
    evidence = "daemon finished in %d ms\nproblems: %s\nsemantic keys: %s" % (out["ms"], json.dumps(out["problems"], indent=1), json.dumps(keys, sort_keys=True))
    record(3, "Valid file main.go: no errors, semantic highlighting", [
        ("daemon finished (%d ms)" % out["ms"], out["ms"] >= 0),
        ("zero ERROR highlights (%d)" % out["errors"], out["errors"] == 0),
        ("no warnings either (%d problems)" % len(out["problems"]), not out["problems"]),
        ("semantic keys present: %s" % ", ".join(sorted(keys)), all(k in keys for k in ("GO_TYPE", "GO_FUNCTION_CALL", "GO_LOCAL_VARIABLE", "GO_PACKAGE", "GO_CONSTANT", "GO_METHOD_CALL"))),
    ], evidence, pictures)


BROKEN_EXPECTED = [
    # (severity, line, highlighted text, substring of the message)
    ("ERROR", 9, "undefinedName", "undefined: undefinedName"),
    ("WARNING", 5, '"os"', '"os" imported and not used'),
    ("WARNING", 13, "unusedVar", "declared and not used: unusedVar"),
    ("ERROR", 17, "1", "cannot use 1 (untyped int constant) as string value in variable declaration"),
    ("ERROR", 26, "}", "missing return"),
]


def step4_broken():
    out = json.loads(js("""
        var e = openFile("broken.go")
        setText(e, __TEXT__)
        var ms = waitDaemon(e)
        var list = infos(e, HighlightSeverity.WEAK_WARNING), res = []
        for (var i = 0; i < list.length; i++) res.push({ sev: String(list[i].getSeverity().getName()), line: lineAt(e, list[i].getStartOffset()), text: String(list[i].getText()), desc: String(list[i].getDescription()) })
        JSON.stringify({ ms: ms, problems: res })
    """, TEXT=source("broken.go")))
    pictures = [shot(4, "broken-file")]
    actual = out["problems"]
    checks = [("daemon finished (%d ms)" % out["ms"], out["ms"] >= 0)]
    unmatched = list(actual)
    for sev, line, text, message in BROKEN_EXPECTED:
        hit = next((p for p in unmatched if p["line"] == line and message in (p["desc"] or "")), None)
        if hit:
            unmatched.remove(hit)
            checks.append(("%s line %d '%s': %s (got %s on '%s')" % (sev, line, text, message, hit["sev"], hit["text"]),
                           hit["sev"] == sev and hit["text"] == text))
        else:
            checks.append(("%s line %d '%s': %s (missing)" % (sev, line, text, message), False))
    checks.append(("nothing else (%d extra: %s)" % (len(unmatched), "; ".join("%s line %d %s" % (p["sev"], p["line"], p["desc"]) for p in unmatched)), not unmatched))
    evidence = "daemon %d ms\n" % out["ms"] + "\n".join("%s line %d '%s': %s" % (p["sev"], p["line"], p["text"], p["desc"]) for p in actual)
    record(4, "broken.go: exactly the expected problems", checks, evidence, pictures)


INTENTION_JS = r"""
function intentionsAt(e, needle, delta) {
    caretTo(e, needle, delta)
    return read(function () {
        var psi = PsiDocumentManager.getInstance(project).getPsiFile(e.getDocument())
        var info = com.intellij.codeInsight.daemon.impl.ShowIntentionsPass.getActionsToShow(e, psi)
        var all = new java.util.ArrayList()
        all.addAll(info.errorFixesToShow); all.addAll(info.inspectionFixesToShow); all.addAll(info.intentionsToShow)
        var res = []
        for (var i = 0; i < all.size(); i++) res.push(all.get(i).getAction())
        return res
    })
}
function applyIntention(e, actions, text) {
    for (var i = 0; i < actions.length; i++) {
        var a = actions[i]
        if (read(function () { return String(a.getText()) }) == text) {
            var psi = psiOf(e)
            return edt(function () { return com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler.chooseActionAndInvoke(psi, e, a, text) ? "invoked" : "not invoked" })
        }
    }
    return "no action " + text
}
function names(actions) { var r = []; for (var i = 0; i < actions.length; i++) { var a = actions[i]; r.push(read(function () { return String(a.getText()) })) } return r }
"""

ADD_IMPORT_BEFORE = """package quickfix

func splitIt() []string {
\treturn strings.Split("a,b", ",")
}
"""


def step5_quickfixes():
    write_scratch("quickfix/addimport.go", ADD_IMPORT_BEFORE)
    out = json.loads(js(INTENTION_JS + """
        var e = openFile("broken.go")
        setText(e, __BROKEN__)
        waitDaemon(e)
        var actions = intentionsAt(e, '"os"', 2)
        var r1 = { offered: names(actions), result: applyIntention(e, actions, "Remove unused import") }
        sleep(500)
        r1.text = textOf(e)
        var f = openFile("quickfix/addimport.go")
        setText(f, __ADD__)
        waitDaemon(f)
        var before = problems(f)
        var actions2 = intentionsAt(f, "strings.Split", 2)
        var r2 = { before: before, offered: names(actions2), result: applyIntention(f, actions2, 'Import "strings"') }
        sleep(500)
        waitDaemon(f)
        r2.text = textOf(f)
        r2.after = problems(f)
        JSON.stringify({ remove: r1, add: r2 })
    """, BROKEN=source("broken.go"), ADD=ADD_IMPORT_BEFORE))
    pictures = [shot(5, "quickfix-add-import")]
    remove, add = out["remove"], out["add"]
    removed_ok = '"os"' not in remove["text"] and ('import (\n\t"fmt"\n)' in remove["text"] or 'import "fmt"' in remove["text"])
    expected_add = 'package quickfix\n\nimport "strings"\n\nfunc splitIt() []string {'
    evidence = "Remove unused import: offered %s -> %s\n%s\n\nAdd import: problems before %s\noffered %s -> %s\n%s\nproblems after: %s" % (
        remove["offered"], remove["result"], remove["text"][:120], add["before"], add["offered"], add["result"], add["text"], add["after"])
    record(5, "Quick fixes: remove unused import, add import", [
        ("'Remove unused import' offered on \"os\"", "Remove unused import" in remove["offered"]),
        ("the import spec is removed, \"fmt\" stays", removed_ok),
        ("'Import \"strings\"' offered on strings.Split", 'Import "strings"' in add["offered"]),
        ("import \"strings\" added after the package clause", add["text"].startswith(expected_add)),
        ("no problems left in addimport.go (%s)" % add["after"], not add["after"]),
    ], evidence, pictures)


COMPLETION_FILE = """package compl

import "strings"

func helperFunc(n int) int { return n }

func use() {
\tlocalVar := 1
\t_ = localVar
\t_ = strings.ToUpper("x")
\t//CARET
}
"""

COMPLETION_JS = r"""
// replaces the //CARET line by `line`, puts the caret at its end, invokes basic completion; chooses `choose` when it is listed
function complete(e, base, line, choose) {
    setText(e, base.replace("\t//CARET", "\t" + line))
    waitDaemon(e, 30000)
    caretTo(e, "\t" + line + "\n", 1 + line.length)
    var items = [], lookupShown = false
    var t0 = now()
    // a lookup is hidden when its editor loses the focus (the sandbox frame is often not the active window): up to 3 attempts
    for (var attempt = 0; attempt < 3 && !lookupShown; attempt++) {
    if (attempt > 0 && lineText(e, caretLine(e)).trim() != line) break   // a single item was inserted
    edt(function () {
        focus(e)
        new com.intellij.codeInsight.completion.CodeCompletionHandlerBase(com.intellij.codeInsight.completion.CompletionType.BASIC, true, false, true).invokeCompletion(project, e)
        return true
    })
    for (var t = 0; t < 40; t++) {
        var got = edt(function () {
            var lookup = com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(e)
            if (lookup == null) return null
            var list = lookup.getItems(), r = []
            for (var i = 0; i < list.size(); i++) r.push(String(list.get(i).getLookupString()))
            return r
        })
        if (got != null && got.length > 0) { items = got; lookupShown = true; break }
        if (t > 10 && lineText(e, caretLine(e)).trim() != line) break   // a single item was inserted
        sleep(100)
    }
    }
    var lookupMs = now() - t0
    var chosen = "lookup " + (lookupShown ? "shown" : "not shown (single item inserted?)")
    if (lookupShown && choose != null) {
        chosen = edt(function () {
            var lookup = com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(e)
            var list = lookup.getItems()
            for (var i = 0; i < list.size(); i++) if (String(list.get(i).getLookupString()) == choose) {
                lookup.setCurrentItem(list.get(i))
                lookup.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
                return "chose " + choose
            }
            lookup.hideLookup(true)
            return "no item " + choose
        })
    } else if (lookupShown) {
        __SHOT__
        edt(function () { com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(e).hideLookup(true); return true })
    }
    sleep(300)
    var text = textOf(e)
    var offset = read(function () { return e.getCaretModel().getOffset() })
    var lineNo = lineAt(e, offset)
    var lt = lineText(e, lineNo)
    var col = offset - read(function () { return e.getDocument().getLineStartOffset(lineNo - 1) })
    return { items: items, count: items.length, lookupMs: lookupMs, chosen: chosen, line: lt.substring(0, col) + "<caret>" + lt.substring(col), text: text }
}
"""


def step6_completion():
    write_scratch("compl/compl.go", COMPLETION_FILE)
    results = {}
    for key, line, choose in (("member", "strings.", None), ("statement", "", None), ("insert", "helperF", "helperFunc"), ("unimported", "time.No", "Now")):
        body = COMPLETION_JS.replace("__SHOT__", "sleep(400)") + """
            var e = openFile("compl/compl.go")
            var r = complete(e, __BASE__, __LINE__, __CHOOSE__)
            JSON.stringify(r)
        """
        results[key] = json.loads(js(body, BASE=COMPLETION_FILE, LINE=line, CHOOSE=choose))
    pictures = [shot(6, "completion")]
    m, s, i, u = results["member"], results["statement"], results["insert"], results["unimported"]
    evidence = "\n".join("%s: %d items, first: %s\n  %s; line: %s" % (k, r["count"], r["items"][:25], r["chosen"], r["line"]) for k, r in results.items())
    evidence += "\n\nunimported, file after:\n" + u["text"]
    record(6, "Completion", [
        ("after `strings.`: Split and Join listed", "Split" in m["items"] and "Join" in m["items"]),
        ("statement position: localVar and helperFunc listed", "localVar" in s["items"] and "helperFunc" in s["items"]),
        ("choosing helperFunc inserts `helperFunc(<caret>)` (%s)" % i["line"].strip(), i["line"].strip() == "helperFunc(<caret>)"),
        ("`time.No` lists Now", "Now" in u["items"]),
        ("choosing Now gives `time.Now()` (%s)" % u["line"].strip(), u["line"].strip() == "time.Now()<caret>"),
        ("\"time\" auto-imported", re.search(r'import \(\s*"strings"\s+"time"\s*\)', u["text"]) is not None),
    ], evidence, pictures)


def step7_navigation():
    out = json.loads(js("""
        var r = {}
        var e = openFile("main.go")
        setText(e, __TEXT__)
        waitDaemon(e)
        caretTo(e, "fmt.Println(describe", 5)
        invoke("GotoDeclaration", e)
        var target = null
        for (var t = 0; t < 100; t++) { sleep(100); var s = selected(); if (s != null && fileName(s).indexOf("main.go") < 0) { target = s; break } }
        r.println = target == null ? "stayed in " + fileName(selected()) : fileName(target) + ":" + caretLine(target) + ": " + lineText(target, caretLine(target)).trim()
        e = openFile("main.go")
        caretTo(e, "s.Name()", 3)
        invoke("GotoDeclaration", e)
        sleep(1500)
        var s2 = selected()
        r.method = fileName(s2).replace(/.*[\\/]/, "") + ":" + caretLine(s2) + ": " + lineText(s2, caretLine(s2)).trim()
        e = openFile("main.go")
        caretTo(e, "r.Width * r.Height", 2)
        invoke("GotoDeclaration", e)
        sleep(1500)
        var s3 = selected()
        r.field = fileName(s3).replace(/.*[\\/]/, "") + ":" + caretLine(s3) + ": " + lineText(s3, caretLine(s3)).trim()
        // Go to Implementation as the action searches: DefinitionsScopedSearch from the interface and from its method
        var psi = psiOf(e)
        function impls(needle) {
            var at = String(textOf(e)).indexOf(needle)
            var el = read(function () { return com.intellij.psi.util.PsiTreeUtil.getParentOfType(psi.findElementAt(at), com.intellij.psi.PsiNameIdentifierOwner, false) })
            var found = com.intellij.psi.search.searches.DefinitionsScopedSearch.search(el).findAll().toArray()
            var res = []
            for (var i = 0; i < found.length; i++) { var f = found[i]; res.push(String(read(function () {
                var d = f.getContainingFile().getViewProvider().getDocument(), ln = d.getLineNumber(f.getTextOffset())
                return f.getContainingFile().getName() + ":" + (ln + 1) + " " + String(d.getText().substring(d.getLineStartOffset(ln), d.getLineEndOffset(ln))).trim()
            }))) }
            return res
        }
        r.implShape = impls("Shape interface")
        r.implArea = impls("Area() float64\\n\\tName")
        // the action itself: one implementation, so it navigates there
        e = openFile("main.go")
        caretTo(e, "Shape interface", 1)
        invoke("GotoImplementation", e)
        sleep(2000)
        var s4 = selected()
        r.gotoImpl = fileName(s4).replace(/.*[\\/]/, "") + ":" + caretLine(s4) + ": " + lineText(s4, caretLine(s4)).trim() + " | popups: " + popupTexts()
        escapeAll()
        e = openFile("main.go")
        waitDaemon(e)
        r.markers = read(function () {
            var doc = e.getDocument()
            var ms = DaemonCodeAnalyzerImpl.getLineMarkers(doc, project), res = []
            for (var i = 0; i < ms.size(); i++) { var m = ms.get(i); var ln = doc.getLineNumber(m.startOffset) + 1; res.push("line " + ln + " " + m.getLineMarkerTooltip() + " | " + String(doc.getText().substring(doc.getLineStartOffset(ln - 1), doc.getLineEndOffset(ln - 1))).trim()) }
            return res
        })
        JSON.stringify(r)
    """, TEXT=source("main.go")))
    pictures = [shot(7, "navigation-gutter")]
    goroot_print = os.path.join(GOROOT, "src", "fmt", "print.go").replace("\\", "/").lower()
    markers = out["markers"]
    evidence = json.dumps(out, indent=1)
    record(7, "Navigation", [
        ("fmt.Println -> $GOROOT/src/fmt/print.go `func Println` (%s)" % out["println"],
         out["println"].lower().startswith(goroot_print) and "func Println(" in out["println"]),
        ("s.Name() -> Shape.Name in main.go:14 (%s)" % out["method"], out["method"].startswith("main.go:14:") and "Name() string" in out["method"]),
        ("r.Width -> field in main.go:19 (%s)" % out["field"], out["field"].startswith("main.go:19:") and "Width" in out["field"]),
        ("implementations of Shape: Rect (%s)" % out["implShape"], any("Rect" in x for x in out["implShape"])),
        ("implementations of Shape.Area: Rect.Area (%s)" % out["implArea"], any("func (r Rect) Area" in x for x in out["implArea"])),
        ("Go to Implementation on Shape navigates to Rect (%s)" % out["gotoImpl"].split(" | ")[0], "type Rect struct" in out["gotoImpl"] or "Rect struct" in out["gotoImpl"]),
        ("gutter markers on Shape (line 12) and Rect.Area (line 23): %d markers" % len(markers),
         any(m.startswith("line 12 ") for m in markers) and any(m.startswith("line 23 ") for m in markers)),
    ], evidence, pictures)


def step8_usages():
    out = json.loads(js("""
        var e = openFile("main.go")
        setText(e, __TEXT__)
        waitDaemon(e)
        var r = {}
        function usages(needle, delta) {
            caretTo(e, needle, delta)
            var target = read(function () { var u = com.intellij.codeInsight.TargetElementUtil.getInstance(); return u.findTargetElement(e, u.getAllAccepted(), e.getCaretModel().getOffset()) })
            if (target == null) return ["no target"]
            var found = com.intellij.psi.search.searches.ReferencesSearch.search(target).findAll().toArray(), res = []
            for (var i = 0; i < found.length; i++) { var ref = found[i]; res.push(read(function () { var el = ref.getElement(); var f = el.getContainingFile(); return String(f.getName()) + ":" + (f.getViewProvider().getDocument().getLineNumber(el.getTextOffset()) + 1) + " " + el.getText() })) }
            return res
        }
        r.describe = usages("func describe", 6)
        r.rect = usages("type Rect struct", 6)
        r.greet = []
        var u = openFile("util/util.go")
        e = u
        r.greet = usages("func Greet", 6)
        // the Show Usages popup (Ctrl+Alt+F7) as the user sees it, for the picture and its rows
        e = openFile("main.go")
        caretTo(e, "type Rect struct", 6)
        invoke("ShowUsages", e)
        sleep(2500)
        r.popup = popupTexts()
        JSON.stringify(r)
    """, TEXT=source("main.go")))
    pictures = [shot(8, "show-usages")]
    js("escapeAll(); true")
    evidence = json.dumps(out, indent=1)
    record(8, "Find Usages", [
        ("describe: 1 usage in main.go (%s)" % out["describe"], len(out["describe"]) == 1 and out["describe"][0].startswith("main.go:")),
        ("Rect: 3 usages (2 receivers, 1 literal) (%d)" % len(out["rect"]), len(out["rect"]) == 3),
        ("util.Greet: 1 usage in main.go (%s)" % out["greet"], len(out["greet"]) == 1 and out["greet"][0].startswith("main.go:")),
        ("Show Usages popup lists 3 rows", out["popup"].count("table:") == 3 or out["popup"].count("row:") == 3),
    ], evidence, pictures)


RENAME_DIALOG_JS = r"""
// the name field of the Rename dialog gets the new name (on the EDT in the dialog's modality), then Refactor
function renameInDialog(name) {
    var field = edtAny(function () {
        var found = null
        function walk(c) {
            if (found != null) return
            if (c instanceof com.intellij.ui.EditorTextField && c.isShowing()) { found = c; return }
            if (c instanceof java.awt.Container) { var k = c.getComponents(); for (var i = 0; i < k.length; i++) walk(k[i]) }
        }
        var ws = java.awt.Window.getWindows()
        for (var w = 0; w < ws.length; w++) if (ws[w].isShowing() && ws[w] instanceof java.awt.Dialog) walk(ws[w])
        return found
    })
    if (field == null) return "no name field"
    var typed = String(edt(function () { field.setText(name); return field.getText() }, 30, ModalityState.stateForComponent(field)))
    sleep(300)
    return "typed " + typed + "; " + clickButton("Refactor")
}
function finishInPlace(e, name) {
    var state = edtAny(function () { return com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(e) })
    if (state == null) return "no in-place template"
    edt(function () {
        var range = state.getCurrentVariableRange()
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { e.getDocument().replaceString(range.getStartOffset(), range.getEndOffset(), name) } }))
        return true
    })
    sleep(300)
    edt(function () { state.gotoEnd(false); return true })
    sleep(800)
    return "renamed in place to " + name
}
"""


def step9_rename():
    evidence = []
    out = json.loads(js(RENAME_DIALOG_JS + """
        var r = {}
        var e = openFile("main.go")
        setText(e, __TEXT__)
        waitDaemon(e)
        // 1. a local variable: in place
        caretTo(e, "total := len", 1)
        invoke("RenameElement", e)
        var state = null
        for (var t = 0; t < 30 && state == null; t++) { sleep(100); state = edtAny(function () { return com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(e) }) }
        r.inplace = state != null
        r.local = state != null ? finishInPlace(e, "count") : "no template; " + popupTexts()
        r.localText = textOf(e)
        // 2. a method that implements Shape.Area: the prompt about the interface method, then the dialog
        caretTo(e, "Rect) Area()", 7)
        invoke("RenameElement", e)
        sleep(2000)
        r.prompt = popupTexts()
        JSON.stringify(r)
    """, TEXT=source("main.go")))
    pictures = [shot(9, "rename-prompt")]
    evidence.append("local: %s" % out["local"])
    evidence.append("prompt after RenameElement on Rect.Area:\n" + out["prompt"])
    second = json.loads(js(RENAME_DIALOG_JS + """
        var r = {}
        r.answer = clickButton("Rename Interface Method")
        sleep(1500)
        r.next = popupTexts()
        var e = edtAny(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
        var state = edtAny(function () { return com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(e) })
        if (state != null) r.done = finishInPlace(e, "Surface")
        else if (r.next.indexOf("Rename") >= 0) r.done = renameInDialog("Surface")
        else r.done = "neither a dialog nor an in-place template"
        sleep(2500)
        r.after = popupTexts()
        escapeAll()
        r.text = textOf(openFile("main.go"))
        JSON.stringify(r)
    """))
    evidence.append("answer: %s\nthen:\n%s\n%s\npopups after: %s" % (second["answer"], second["next"], second["done"], second["after"]))
    evidence.append(second["text"])
    pictures.append(shot(9, "rename-after"))
    local_text, text = out["localText"], second["text"]
    record(9, "Rename", [
        ("local variable renamed in place (template started: %s)" % out["inplace"], out["inplace"]),
        ("`count := len(parts)` and its use renamed, no `total` left",
         "count := len(parts)" in local_text and ", count, Max(" in local_text and "total" not in local_text),
        ("renaming Rect.Area asks 'Rename the interface method and all its implementations?'", "Rename the interface method" in out["prompt"]),
        ("Rect.Area, Shape.Area and the call s.Area() renamed to Surface, no `Area()` left in code",
         "func (r Rect) Surface() float64" in text and "\tSurface() float64" in text and "s.Surface()" in text and "Area()" not in text),
    ], "\n\n".join(evidence), pictures)


def step10_structure():
    out = js("""
        var e = openFile("main.go")
        setText(e, __TEXT__)
        waitDaemon(e)
        read(function () {
            var psi = PsiDocumentManager.getInstance(project).getPsiFile(e.getDocument())
            var builder = com.intellij.lang.LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi)
            var model = builder.createStructureViewModel(e)
            var out = ""
            var top = model.getRoot().getChildren()
            for (var i = 0; i < top.length; i++) {
                out += top[i].getPresentation().getPresentableText() + "\\n"
                var kids = top[i].getChildren()
                for (var j = 0; j < kids.length; j++) out += "    " + kids[j].getPresentation().getPresentableText() + "\\n"
            }
            model.dispose()
            return out
        })
    """, TEXT=source("main.go"))
    js('invoke("ActivateStructureToolWindow", selected()); sleep(1500); true')
    pictures = [shot(10, "structure")]
    js('invoke("HideActiveWindow", selected()); sleep(300); true')
    lines = out.splitlines()
    tops = [l for l in lines if not l.startswith(" ")]

    def children(name):
        result, inside = [], False
        for l in lines:
            if not l.startswith(" "):
                inside = l.startswith(name)
            elif inside:
                result.append(l.strip())
        return result
    record(10, "Structure view", [
        ("top level has Shape, Rect, Color, Red, Green, Blue, Max, describe, parse, main",
         all(any(t.startswith(n) for t in tops) for n in ("Shape", "Rect", "Color", "Red", "Green", "Blue", "Max", "describe", "parse", "main"))),
        ("Rect has methods Area and Name", any(c.startswith("Area") for c in children("Rect")) and any(c.startswith("Name") for c in children("Rect"))),
        ("Shape lists its methods Area and Name", any(c.startswith("Area") for c in children("Shape")) and any(c.startswith("Name") for c in children("Shape"))),
    ], out, pictures)


def step11_folding():
    out = json.loads(js("""
        var e = openFile("main.go")
        setText(e, __TEXT__)
        waitDaemon(e)
        sleep(500)
        var r = read(function () {
            var doc = e.getDocument()
            var regions = e.getFoldingModel().getAllFoldRegions(), res = []
            for (var i = 0; i < regions.length; i++) {
                var f = regions[i]
                res.push({ from: doc.getLineNumber(f.getStartOffset()) + 1, to: doc.getLineNumber(f.getEndOffset()) + 1, placeholder: String(f.getPlaceholderText()),
                           start: String(doc.getText().substring(f.getStartOffset(), Math.min(f.getEndOffset(), f.getStartOffset() + 20))) })
            }
            return res
        })
        // collapse everything for the picture, expand back afterwards
        invoke("CollapseAllRegions", e)
        sleep(700)
        JSON.stringify(r)
    """, TEXT=source("main.go")))
    pictures = [shot(11, "folded")]
    js('invoke("ExpandAllRegions", selected()); sleep(300); true')
    evidence = "\n".join("%d-%d %s  %r" % (f["from"], f["to"], f["placeholder"], f["start"]) for f in out)
    spans = {(f["from"], f["to"]) for f in out}
    bodies = [(23, 25), (28, 30), (42, 47), (49, 51), (53, 58), (60, 73)]
    record(11, "Folding", [
        ("import group (lines 3-9) foldable", (3, 9) in spans),
        ("function and method bodies foldable %s" % [b for b in bodies if b not in spans], all(b in spans for b in bodies)),
    ], evidence, pictures)


def step12_reformat():
    raw = source("fmtcheck/misformatted.go.txt")
    write_scratch("fmtcheck/misformatted.go", raw)
    gofmt = subprocess.run([GOFMT], input=raw.encode("utf-8"), capture_output=True, check=False)
    expected = gofmt.stdout.decode("utf-8")
    out = js("""
        var e = openFile("fmtcheck/misformatted.go")
        setText(e, __TEXT__)
        waitDaemon(e)
        invoke("ReformatCode", e)
        var before = textOf(e), after = before
        for (var t = 0; t < 50; t++) { sleep(200); after = textOf(e); if (after != before) { sleep(800); after = textOf(e); break } }
        after
    """, TEXT=raw)
    pictures = [shot(12, "reformatted")]
    import difflib
    diff = "".join(difflib.unified_diff(expected.splitlines(True), out.splitlines(True), "gofmt", "IDE Reformat Code"))
    record(12, "Reformat Code equals gofmt", [
        ("gofmt ran (exit %d)" % gofmt.returncode, gofmt.returncode == 0),
        ("the IDE text is exactly gofmt's output", out == expected),
    ], (diff or "identical") + "\n\n--- IDE result ---\n" + out, pictures)


def step13_docs():
    out = json.loads(js("""
        var e = openFile("main.go")
        setText(e, __TEXT__)
        waitDaemon(e)
        var r = {}
        // Quick Documentation through the platform's documentation targets, as Ctrl+Q computes them
        var offset = caretTo(e, "fmt.Println(describe", 6)
        var psi = psiOf(e)
        r.doc = read(function () {
            var leaf = psi.findElementAt(offset)
            var ref = psi.findReferenceAt(offset)
            var target = ref == null ? null : ref.resolve()
            if (target == null) return "unresolved"
            var providers = com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider.EP_NAME.getExtensionList()
            for (var i = 0; i < providers.size(); i++) {
                var targets = providers.get(i).documentationTargets(target, leaf)
                if (targets != null && targets.size() > 0) {
                    var result = targets.get(0).computeDocumentation()
                    if (result == null) return "null documentation"
                    try { return String(result.getHtml()) } catch (x) { return "result " + result }
                }
            }
            return "no documentation target"
        })
        invoke("QuickJavaDoc", e)
        sleep(2500)
        r.popup = popupTexts()
        JSON.stringify(r)
    """, TEXT=source("main.go")))
    pictures = [shot(13, "quick-doc", "//div[@class='DocumentationPopupPane' or contains(@classhierarchy, 'DocumentationPopupPane')]")]
    js("escapeAll(); true")
    # Parameter Info inside fmt.Printf(
    pi = json.loads(js("""
        var e = selected()
        var offset = caretTo(e, 'fmt.Printf("%d', 11)
        invoke("ParameterInfo", e)
        sleep(2000)
        var r = { popup: popupTexts() }
        r.hint = edt(function () {
            var out = ""
            function walk(c) {
                if (c.getClass().getSimpleName().indexOf("ParameterInfoComponent") >= 0 || c.getClass().getName().indexOf("ParameterInfo") >= 0) out += c.getClass().getSimpleName() + ": " + texts(c) + "\\n"
                if (c instanceof java.awt.Container) { var k = c.getComponents(); for (var i = 0; i < k.length; i++) walk(k[i]) }
            }
            function texts(c) {
                var t = ""
                if (c instanceof javax.swing.JLabel || c instanceof javax.swing.text.JTextComponent) t += String(c.getText()).replace(/<[^>]*>/g, "") + " "
                if (c instanceof java.awt.Container) { var k = c.getComponents(); for (var i = 0; i < k.length; i++) t += texts(k[i]) }
                return t
            }
            var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
            walk(frame.getLayeredPane())
            var ws = java.awt.Window.getWindows()
            for (var w = 0; w < ws.length; w++) if (ws[w].isShowing() && !(ws[w] instanceof javax.swing.JFrame)) walk(ws[w])
            return out
        })
        JSON.stringify(r)
    """))
    pictures.append(shot(13, "parameter-info"))
    js("escapeAll(); true")
    doc_text = re.sub(r"<[^>]+>", " ", out["doc"])
    doc_text = re.sub(r"\s+", " ", doc_text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"))
    hint = pi["hint"] + pi["popup"]
    evidence = "doc (text): %s\n\nQuickJavaDoc popup:\n%s\n\nParameter Info:\n%s\n%s" % (doc_text, out["popup"][:3000], pi["hint"], pi["popup"][:2000])
    record(13, "Quick Documentation and Parameter Info", [
        ("doc has the signature `func Println(a ...any) (n int, err error)`", "func Println(a ...any) (n int, err error)" in doc_text),
        ("doc has the doc text (`Println formats using the default formats`)", "Println formats using the default formats" in doc_text),
        ("Quick Documentation popup shows it", "Println formats using the default formats" in re.sub(r"<[^>]+>", " ", out["popup"])),
        ("Parameter Info shows `format string, a ...any`", "format string, a ...any" in re.sub(r"<[^>]+>", "", hint)),
    ], evidence, pictures)


def step14_latency():
    server = os.path.join(GOROOT, "src", "net", "http", "server.go")
    with open(server, encoding="utf-8") as f:
        text = f.read()
    write_scratch("httpsrv/server.go", text)
    out = json.loads(js("""
        var r = {}
        var t0 = now()
        var e = openFile("httpsrv/server.go")
        r.firstDaemon = waitDaemon(e, 180000)
        r.openToHighlighted = now() - t0
        // inside the body of (*conn).serve: a new line after its first statement
        var at = caretTo(e, "func (c *conn) serve(ctx context.Context) {\\n", 0)
        edt(function () {
            var doc = e.getDocument()
            var line = doc.getLineNumber(at) + 1
            e.getCaretModel().moveToOffset(doc.getLineEndOffset(line))
            return true
        })
        // Enter, then 20 characters through the typed-action handler (what a key press runs), 60 ms apart
        edt(function () {
            var ctx = com.intellij.openapi.editor.ex.util.EditorUtil.getEditorDataContext(e)
            // inside a command, as the key dispatcher runs it (undo and the typed handlers need one)
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, new java.lang.Runnable({ run: function () {
                com.intellij.openapi.editor.actionSystem.EditorActionManager.getInstance().getActionHandler("EditorEnter").execute(e, e.getCaretModel().getCurrentCaret(), ctx)
            } }), "Enter", null)
            return true
        })
        var chars = "var zz = 12345678901"
        var perKey = []
        for (var i = 0; i < chars.length; i++) {
            var c = chars.charAt(i)
            var k0 = java.lang.System.nanoTime()
            edt(function () {
                var ctx = com.intellij.openapi.editor.ex.util.EditorUtil.getEditorDataContext(e)
                com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, new java.lang.Runnable({ run: function () {
                    com.intellij.openapi.editor.actionSystem.TypedAction.getInstance().actionPerformed(e, c.charAt(0), ctx)
                } }), "Typing", null)
                return true
            })
            perKey.push((java.lang.System.nanoTime() - k0) / 1000000)
            sleep(60)
        }
        // from the last keystroke until every pass has finished on the file (polled every 50 ms on the EDT)
        r.daemonAfterLastKeyMs = waitDaemon(e, 120000)
        r.typedLine = lineText(e, caretLine(e)).trim()
        perKey.sort(function (a, b) { return a - b })
        r.keyMaxMs = perKey[perKey.length - 1]
        r.keyMedianMs = perKey[Math.floor(perKey.length / 2)]
        r.problemsNearCaret = []
        var ps = problems(e)
        for (var i = 0; i < ps.length; i++) if (ps[i].indexOf("zz") >= 0) r.problemsNearCaret.push(ps[i])
        r.problemCount = ps.length
        r.firstProblems = ps.slice(0, 10)
        // Go to Declaration latency: a call of a method declared further down
        caretTo(e, "c.readRequest(ctx)", 3)
        var g0 = now()
        invoke("GotoDeclaration", e)
        var line0 = caretLine(e), moved = -1
        for (var t = 0; t < 400; t++) { sleep(10); if (caretLine(e) != line0) { moved = now() - g0; break } }
        r.gotoMs = moved
        r.gotoTarget = lineText(e, caretLine(e)).trim()
        // the resolve alone, timed inside the IDE
        caretTo(e, "c.readRequest(ctx)", 3)
        var off = read(function () { return e.getCaretModel().getOffset() })
        var psi = psiOf(e)
        var r0 = java.lang.System.nanoTime()
        r.resolved = read(function () { var ref = psi.findReferenceAt(off); var t = ref == null ? null : ref.resolve(); return t == null ? "null" : String(t.getText()).split("\\n")[0] })
        r.resolveMs = (java.lang.System.nanoTime() - r0) / 1000000
        // undo the typing so the file stays as copied
        edt(function () { FileDocumentManager.getInstance().reloadFromDisk(e.getDocument()); return true })
        JSON.stringify(r)
    """, timeout=600))
    pictures = [shot(14, "server-go")]
    evidence = json.dumps(out, indent=1)
    record(14, "Typing and Go to Declaration latency in net/http/server.go", [
        ("first highlighting finished (%d ms after open)" % out["openToHighlighted"], out["firstDaemon"] >= 0),
        ("typed `var zz = 12345678901` (%s)" % out["typedLine"], out["typedLine"] == "var zz = 12345678901"),
        ("daemon finished %d ms after the last keystroke (budget 3000 ms; includes the platform's autoreparse delay and 50 ms polling); keys max %d ms, median %d ms" % (
            out["daemonAfterLastKeyMs"], out["keyMaxMs"], out["keyMedianMs"]), 0 <= out["daemonAfterLastKeyMs"] <= 3000),
        ("`zz` reported as unused", any("zz" in p for p in out["problemsNearCaret"])),
        ("Go to Declaration on c.readRequest reached `func (c *conn) readRequest` in %d ms (resolve %d ms)" % (out["gotoMs"], out["resolveMs"]),
         out["gotoMs"] >= 0 and "func (c *conn) readRequest" in out["gotoTarget"]),
    ], evidence, pictures)


def step15_exit(attach):
    evidence = []
    res = js("""
        var name = String(project.getName())
        edt(function () { FileDocumentManager.getInstance().saveAllDocuments(); return true })
        later(function () { com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().closeAndDispose(project) })
        var t = 0
        while (t < 100 && ProjectManager.getInstance().getOpenProjects().length > 0) { sleep(200); t++ }
        "closed " + name + ", open projects now: " + ProjectManager.getInstance().getOpenProjects().length
    """)
    evidence.append(res)
    closed = res.endswith(": 0")
    time.sleep(2)
    pictures = [shot(15, "welcome")]
    step2_log(final=True)
    if attach:
        record(15, "Close the project (IDE kept: --attach)", [("project closed", closed)], "\n".join(evidence), pictures)
        return
    try:
        js("later(function () { ApplicationManager.getApplication().exit(true, true, false) }); true")
    except Exception as e:  # noqa: BLE001 - the robot reports any failure as evidence
        evidence.append("exit call: %s" % str(e).splitlines()[0])
    ended, code = False, None
    try:
        code = GRADLE.wait(timeout=180)
        ended = True
    except subprocess.TimeoutExpired:
        evidence.append("gradle still running after 180 s")
    with open(os.path.join(OUT, "gradle-runIdeForUiTests.log"), encoding="utf-8", errors="replace") as f:
        tail = f.read().splitlines()[-5:]
    evidence.append("gradle exit code %s; log tail:\n%s" % (code, "\n".join(tail)))
    record(15, "Close the project and exit the IDE", [
        ("project closed", closed), ("gradle task ended (exit %s)" % code, ended and code == 0), ("robot port closed", not port_open()),
    ], "\n".join(evidence), pictures)


STEPS = {3: step3_valid, 4: step4_broken, 5: step5_quickfixes, 6: step6_completion, 7: step7_navigation, 8: step8_usages,
         9: step9_rename, 10: step10_structure, 11: step11_folding, 12: step12_reformat, 13: step13_docs, 14: step14_latency}


USAGE_ERROR = 2


def parse_args(argv):
    """--attach, --steps N,M, --perf, --scenario, --cold, --help; anything else prints the usage and runs nothing."""
    opts = {"attach": False, "steps": None, "perf": False, "scenario": False, "cold": False}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("--help", "-h", "/?"):
            print(__doc__)
            raise SystemExit(0)
        if a in ("--attach", "--perf", "--scenario", "--cold"):
            opts[a[2:]] = True
        elif a == "--steps" and i + 1 < len(argv) and re.fullmatch(r"\d+(,\d+)*", argv[i + 1]):
            opts["steps"] = {int(x) for x in argv[i + 1].split(",")}
            i += 1
        else:
            print("autotest: unknown or incomplete option %r\n%s" % (a, __doc__))
            raise SystemExit(USAGE_ERROR)
        i += 1
    if opts["cold"] and opts["attach"]:
        print("autotest: --cold needs a fresh start of the IDE; it cannot be combined with --attach\n" + __doc__)
        raise SystemExit(USAGE_ERROR)
    return opts


def main():
    global MEASURE_OPEN
    opts = parse_args(sys.argv[1:])
    attach = opts["attach"]
    wanted = opts["steps"]
    if wanted is None and opts["perf"] and not opts["scenario"]:
        wanted = {1, 2, 15}
    os.makedirs(OUT, exist_ok=True)
    MEASURE_OPEN = opts["perf"]
    if wanted is None or 1 in wanted:
        step1_start(attach, opts["cold"])
    if wanted is None or 2 in wanted:
        step2_log()
    if opts["perf"]:
        # completion is cold only when nothing used it before in this IDE session
        perf.run(sys.modules[__name__], " ".join(sys.argv[1:]), FIRST_OPEN, cold_completion=not attach)
    for number, function in STEPS.items():
        if wanted is not None and number not in wanted:
            continue
        # dialogs left open block every non-modal EDT call; the "IDE error occurred" balloon in the popup layer does not
        leftover = js("popupTexts()")
        if "Dialog '" in leftover:
            print("       closing leftovers before step %d: %s" % (number, leftover.replace(chr(10), " | ")[:300]))
            js('clickButton("Load File System Changes"); escapeAll(); true')
        try:
            function()
        except Exception as e:  # noqa: BLE001 - the robot reports any failure as evidence
            record(number, function.__name__, [("step ran without a harness error", False)], traceback.format_exc() + "\n" + str(e), [])
            try:
                js("escapeAll(); true")
            except Exception:  # noqa: BLE001, S110 - best effort cleanup
                pass
    if wanted is None or 15 in wanted:
        step15_exit(attach)
    failed = [r for r in RESULTS if not r["ok"]]
    print("report: %s (%d steps, %d failed)" % (os.path.join(OUT, "report.md"), len(RESULTS), len(failed)))


if __name__ == "__main__":
    main()
