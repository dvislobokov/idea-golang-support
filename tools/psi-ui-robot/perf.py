"""Performance mode of the UI robot scenario (`autotest.py --perf`): live editing latency in the sandbox IDE, baselines only.

Every number is taken inside the IDE (System.nanoTime in the robot's script thread or on the EDT), never across HTTP. Daemon
timings come from a DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC listener (daemonStarting / daemonFinished per editor, annotator
statistics); the listener is a java.lang.reflect.Proxy because Rhino's interface adapters do not override default methods.

Writes build/ui-robot/perf.md, build/ui-robot/perf.json and a timestamped copy perf-YYYYMMDD-HHMMSS.json for comparisons
(`python tools/ui-robot/perf_compare.py OLD.json NEW.json`). Screenshots only when a perf step fails.
"""
import json
import os
import platform
import re
import shutil
import statistics
import subprocess
import time
import traceback

AT = None  # the autotest module (set by run()/measure_open())

PERF = {}  # key -> {"what", "unit", "samples", "median", "max", "min", "n", "failed", "extra"}
NOTES = {}  # P-step -> list of strings
HEAP = {}
META = {}
CHECKS = []  # (step, description, ok)

REPEAT = 5
P1_KEYS = 20
OPEN_FILES = [
    ("go/types/expr.go", "open/typesexpr/expr.go"),
    ("go/types/call.go", "open/typescall/call.go"),
    ("go/parser/parser.go", "open/parser/parser.go"),
    ("net/http/transport.go", "open/transport/transport.go"),
    ("runtime/proc.go", "open/runtime/proc.go"),
]

# ------------------------------------------------------------------------------------------------------------------------------
# JavaScript put in front of every perf script (after autotest's HEADER). `nano()` is milliseconds (fractional) since the script
# started; daemon events land in EVENTS as "<ms> <kind>|file1|file2|".

PERF_JS = r"""
importClass(com.intellij.openapi.util.Disposer)
importClass(com.intellij.openapi.command.CommandProcessor)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.openapi.editor.ex.util.EditorUtil)
importClass(com.intellij.psi.search.GlobalSearchScope)
var BASE_NS = java.lang.System.nanoTime()
function nano() { return (java.lang.System.nanoTime() - BASE_NS) / 1000000 }
var EVENTS = new java.util.concurrent.ConcurrentLinkedQueue()
var ANNOT = new java.util.concurrent.ConcurrentLinkedQueue()
var LISTEN = Disposer.newDisposable("robot-perf")
;(function () {
    var loader = DaemonCodeAnalyzer.getInstance(project).getClass().getClassLoader()
    var iface = java.lang.Class.forName("com.intellij.codeInsight.daemon.DaemonCodeAnalyzer$DaemonListener", true, loader)
    function names(editors) {
        var s = "|"
        if (editors == null) return s
        var it = editors.iterator()
        while (it.hasNext()) { var f = it.next().getFile(); s += (f == null ? "?" : f.getName()) + "|" }
        return s
    }
    var handler = new java.lang.reflect.InvocationHandler({ invoke: function (proxy, method, args) {
        var n = String(method.getName())
        if (n == "hashCode") return new java.lang.Integer(4242)
        if (n == "equals") return new java.lang.Boolean(proxy === args[0])
        if (n == "toString") return "robot-perf-listener"
        var t = nano()
        if (n == "daemonStarting" && args != null) EVENTS.add(t + " start" + names(args[0]))
        else if (n == "daemonFinished" && args != null && args.length == 1) EVENTS.add(t + " finish" + names(args[0]))
        else if (n == "daemonCanceled" && args != null && args.length == 2) EVENTS.add(t + " cancel" + names(args[1]))
        else if (n == "daemonAnnotatorStatisticsGenerated" && args != null) {
            var it = args[1].iterator()
            while (it.hasNext()) {
                var st = it.next()
                if (String(st.annotator.getClass().getName()).indexOf("gopsi") >= 0)
                    ANNOT.add(t + " " + ((st.annotatorFinishStamp - st.annotatorStartStamp) / 1000000) + "|" + args[2].getName() + "|")
            }
        }
        return null
    } })
    project.getMessageBus().connect(LISTEN).subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, java.lang.reflect.Proxy.newProxyInstance(loader, [iface], handler))
})()
function nameOf(e) { return String(read(function () { var f = FileDocumentManager.getInstance().getFile(e.getDocument()); return f == null ? "?" : f.getName() })) }
// the last event of `kind` for file `name` after time t (-1 if none)
function lastEvent(kind, name, t) {
    var arr = EVENTS.toArray(), last = -1
    for (var i = 0; i < arr.length; i++) {
        var s = String(arr[i]), sp = s.indexOf(" "), bar = s.indexOf("|")
        var ts = Number(s.substring(0, sp))
        if (ts > t && s.substring(sp + 1, bar) == kind && s.indexOf("|" + name + "|") >= 0 && ts > last) last = ts
    }
    return last
}
function firstEvent(kind, name, t) {
    var arr = EVENTS.toArray(), first = -1
    for (var i = 0; i < arr.length; i++) {
        var s = String(arr[i]), sp = s.indexOf(" "), bar = s.indexOf("|")
        var ts = Number(s.substring(0, sp))
        if (ts > t && s.substring(sp + 1, bar) == kind && s.indexOf("|" + name + "|") >= 0 && (first < 0 || ts < first)) first = ts
    }
    return first
}
function countEvents(kind, name, t) {
    var arr = EVENTS.toArray(), n = 0
    for (var i = 0; i < arr.length; i++) { var s = String(arr[i]), sp = s.indexOf(" "); if (Number(s.substring(0, sp)) > t && s.substring(sp + 1, s.indexOf("|")) == kind && s.indexOf("|" + name + "|") >= 0) n++ }
    return n
}
// the go-psi annotator time (ms) of the last daemon run on `name` after t
function annotMs(name, t) {
    var arr = ANNOT.toArray(), last = -1, v = -1
    for (var i = 0; i < arr.length; i++) {
        var s = String(arr[i]), sp = s.indexOf(" "), bar = s.indexOf("|"), ts = Number(s.substring(0, sp))
        if (ts > t && s.indexOf("|" + name + "|") >= 0 && ts > last) { last = ts; v = Number(s.substring(sp + 1, bar)) }
    }
    return v
}
// every pass finished on the file of `e`, nothing pending, all documents committed (does not force a commit)
function idle(e) {
    return edt(function () {
        var pdm = PsiDocumentManager.getInstance(project)
        if (pdm.hasUncommitedDocuments() || DumbService.getInstance(project).isDumb()) return false
        var d = DaemonCodeAnalyzer.getInstance(project)
        return !d.isRunningOrPending() && d.isAllAnalysisFinished(pdm.getPsiFile(e.getDocument()))
    })
}
// one EDT round trip (ms): how long a runnable posted now waits for the EDT
function stall() { var p = nano(); edt(function () { return true }); return nano() - p }
// waits until the daemon has finished the file of `e` after time t: { ms (t -> last daemonFinished), startMs (t -> first
// daemonStarting), runMs (that start -> finish), restarts, maxStallMs (EDT round trips meanwhile), annotMs }. With `noRunMs`, a
// file the daemon never started on within that time (and that is idle) counts as done: { ms: 0, restarts: 0, noRun: true }.
function awaitDaemon(e, t, timeout, extra, noRunMs) {
    var name = nameOf(e), maxStall = 0, deadline = nano() + (timeout || 120000)
    while (nano() < deadline) {
        var s = stall()
        if (s > maxStall) maxStall = s
        if (noRunMs != null && nano() - t > noRunMs && firstEvent("start", name, t) < 0 && idle(e))
            return { ms: 0, startMs: -1, runMs: 0, restarts: 0, maxStallMs: maxStall, annotMs: 0, noRun: true }
        if (lastEvent("finish", name, t) > 0 && (extra == null || extra()) && idle(e)) {
            var fin = lastEvent("finish", name, t), st = firstEvent("start", name, t), lastStart = -1
            var arr = EVENTS.toArray()
            for (var i = 0; i < arr.length; i++) { var x = String(arr[i]); var ts = Number(x.substring(0, x.indexOf(" "))); if (ts > t && ts <= fin && x.indexOf(" start|") > 0 && x.indexOf("|" + name + "|") >= 0) lastStart = ts }
            return { ms: fin - t, startMs: st < 0 ? -1 : st - t, runMs: lastStart < 0 ? -1 : fin - lastStart, restarts: countEvents("start", name, t),
                     maxStallMs: maxStall, annotMs: annotMs(name, t) }
        }
        sleep(15)
    }
    return { ms: -1, startMs: -1, runMs: -1, restarts: countEvents("start", name, t), maxStallMs: maxStall, annotMs: -1 }
}
// the daemon is done with `e` and stays done for a moment
function quiet(e) {
    waitDaemon(e, 180000)
    for (var i = 0; i < 200; i++) { sleep(100); if (idle(e)) { sleep(150); if (idle(e)) return true } }
    return false
}
function write(fn) { edt(function () { WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: fn })); return true }) }
// a key press as the dispatcher runs it: the typed-action handler inside a command; t = EDT time when the handler started
function typeKey(e, ch) {
    var before = nano()
    var r = edt(function () {
        var ctx = EditorUtil.getEditorDataContext(e)
        var a = nano()
        CommandProcessor.getInstance().executeCommand(project, new java.lang.Runnable({ run: function () {
            TypedAction.getInstance().actionPerformed(e, ch.charAt(0), ctx)
        } }), "Typing", null)
        return [a, nano() - a]
    })
    return { t: r[0], edtMs: r[1], roundtripMs: nano() - before }
}
// start offsets of the first body line of every top-level function with a multi-line body
function bodies(text) {
    var re = /\nfunc [^\n]*\{\n\t/g, m, out = []
    while ((m = re.exec(text)) != null) out.push({ at: m.index + m[0].length - 1, sig: m[0].substring(1, m[0].indexOf("{")).trim() })
    return out
}
// escapeAll() leaves a documentation popup open at times: cancel every JBPopup of the frame and of the showing windows
function closePopups() {
    escapeAll()
    edtAny(function () {
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            var ps = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(frame.getRootPane())
            for (var i = 0; i < ps.size(); i++) ps.get(i).cancel()
        }
        var ws = java.awt.Window.getWindows()
        for (var w = 0; w < ws.length; w++) if (ws[w].isShowing() && !(ws[w] instanceof javax.swing.JFrame) && !(ws[w] instanceof java.awt.Dialog) && ws[w].getComponentCount() > 0) {
            var p = com.intellij.openapi.ui.popup.util.PopupUtil.getPopupContainerFor(ws[w].getComponent(0))
            if (p != null) p.cancel()
        }
        return true
    })
    sleep(100)
}
function selectedState() {
    return edt(function () {
        var s = FileEditorManager.getInstance(project).getSelectedTextEditor()
        if (s == null) return ["", -1]
        var f = FileDocumentManager.getInstance().getFile(s.getDocument())
        return [String(f == null ? "?" : f.getPath()), s.getCaretModel().getLogicalPosition().line]
    })
}
"""


def js(body, timeout=600, **values):
    """autotest.js with PERF_JS in front; `body` is a function body (ends with `return ...`), the listener is always disposed."""
    script = PERF_JS + "\n(function () { try {\n" + body + "\n} finally { Disposer.dispose(LISTEN) } })()"
    return AT.js(script, timeout=timeout, **values)


def jsj(body, timeout=600, **values):
    return json.loads(js(body, timeout=timeout, **values))


# ------------------------------------------------------------------------------------------------------------------------------
# Results


def put(key, samples, what, unit="ms", extra=None):
    valid = [round(float(s), 1) for s in samples if s is not None and float(s) >= 0]
    entry = {"what": what, "unit": unit, "samples": valid, "n": len(valid), "failed": len(samples) - len(valid)}
    if valid:
        entry.update(median=round(statistics.median(valid), 1), max=round(max(valid), 1), min=round(min(valid), 1))
    else:
        entry.update(median=None, max=None, min=None)
    if extra:
        entry["extra"] = extra
    PERF[key] = entry
    print("       %-34s n=%-2d median %8s  max %8s %s" % (key, entry["n"], entry["median"], entry["max"], unit))
    return entry


def note(step, text):
    NOTES.setdefault(step, []).append(text)


def check(step, description, ok):
    CHECKS.append((step, description, bool(ok)))
    if not ok:
        print("       FAILED: %s %s" % (step, description))
    return ok


def fail_shot(step):
    try:
        return AT.shot(90 + int(step[1:]), "perf-%s-failed" % step.lower())
    except Exception as e:  # noqa: BLE001
        return "no screenshot (%s)" % e


TITLES = {
    "P1": "Typing in function bodies of net/http/server.go (copy)",
    "P2": "Top-level edit in a.go, highlighting of the open b.go",
    "P3": "Completion popup",
    "P4": "Go to Declaration, Find Usages, Quick Documentation",
    "P5": "Reformat Code on server.go",
    "P6": "Opening a large file",
    "P7": "Project open to smart mode (indexing)",
    "P8": "Declaration edits and sibling-file edits, server.go open",
    "P9": "Memory retained by the semantic caches",
}


def write_outputs():
    os.makedirs(AT.OUT, exist_ok=True)
    data = {"meta": META, "measurements": PERF, "heap": HEAP, "notes": NOTES,
            "checks": [{"step": s, "check": d, "ok": ok} for s, d, ok in CHECKS]}
    with open(os.path.join(AT.OUT, "perf.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=1, sort_keys=False)
    lines = ["# go-psi UI robot: editing performance", "",
             "%s, IDE %s, go-psi %s, %s CPUs, -Xmx %s MB, autoreparse delay %s ms, %s." % (
                 META.get("date"), META.get("ide"), META.get("plugin"), META.get("cpus"), META.get("xmxMb"),
                 META.get("autoReparseDelayMs"), META.get("flags")), "",
             "Times in ms measured inside the IDE (System.nanoTime), %d repetitions unless n says otherwise; "
             "`daemon` = until DaemonCodeAnalyzer reported daemonFinished for the editor and every pass is done." % REPEAT, ""]
    for step in sorted(TITLES):
        keys = [k for k in PERF if k.startswith(step + ".")]
        step_checks = [c for c in CHECKS if c[0] == step]
        if not keys and not step_checks:
            continue
        lines += ["## %s. %s" % (step, TITLES[step]), "", "| Metric | n | median | max | min | What |", "|---|---|---|---|---|---|"]
        for k in keys:
            m = PERF[k]
            lines.append("| `%s` | %d%s | %s | %s | %s | %s |" % (k, m["n"], (" (%d failed)" % m["failed"]) if m["failed"] else "",
                                                             fmt(m["median"], m["unit"]), fmt(m["max"], m["unit"]), fmt(m["min"], m["unit"]), m["what"]))
        lines.append("")
        for d, ok in [(c[1], c[2]) for c in step_checks]:
            lines.append("- [%s] %s" % ("x" if ok else " ", d))
        for n in NOTES.get(step, []):
            lines.append("- %s" % n)
        lines.append("")
    if HEAP:
        lines += ["## JVM heap after GC", "", "| Point | used MB | committed MB |", "|---|---|---|"]
        for k, v in HEAP.items():
            lines.append("| %s | %s | %s |" % (k, v.get("usedMb"), v.get("committedMb")))
        lines.append("")
    with open(os.path.join(AT.OUT, "perf.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def fmt(v, unit):
    if v is None:
        return "-"
    return ("%.0f" % v if unit == "ms" and v >= 100 else "%.1f" % v) if isinstance(v, float) else str(v)


# ------------------------------------------------------------------------------------------------------------------------------
# Setup


def goroot_text(rel):
    with open(os.path.join(AT.GOROOT, "src", *rel.split("/")), encoding="utf-8") as f:
        return f.read()


PERFPKG_A = """package perfpkg

// Compute adds two numbers.
func Compute(a int, b int) int {
\treturn a + b
}

// Scale multiplies v by k.
func Scale(v, k int) int { return v * k }
"""

PERFPKG_B = """package perfpkg

import "fmt"

func useCompute() int {
\tx := Compute(1, 2)
\ty := Compute(x, 3)
\tz := Scale(Compute(y, 4), 2)
\tfmt.Println(x, y, z)
\treturn x + y + z
}

type acc struct{ total int }

func (a *acc) add(v int) { a.total = Compute(a.total, v) }
"""

PERFPKG_C = """package perfpkg

// twice applies Compute to v and itself.
func twice(v int) int { return Compute(v, v) }

var table = []int{twice(1), twice(2), Scale(3, 4)}
"""

COMPL_FILE = """package perfcompl

import (
\t"fmt"
\t"net/http"
)

func handle(w http.ResponseWriter, r *http.Request) {
\tfmt.Fprintln(w, r.Method)
\t//CARET
}
"""

SIG_OLD = "func Compute(a int, b int) int {"
SIG_NEW = "func Compute(a int, b int, c int) int {"


def setup_files():
    """Creates the perf files of the scratch project once (write_scratch never overwrites an existing file), refreshes the VFS
    and waits for smart mode."""
    AT.write_scratch("httpsrv/server.go", goroot_text("net/http/server.go"))
    AT.write_scratch("perfpkg/a.go", PERFPKG_A)
    AT.write_scratch("perfpkg/b.go", PERFPKG_B)
    AT.write_scratch("perfpkg/c.go", PERFPKG_C)
    AT.write_scratch("perfcompl/compl.go", COMPL_FILE)
    AT.write_scratch("httpsrv/perfextra.go", PERF_EXTRA)
    for src, dst in OPEN_FILES + MEM_FILES:
        AT.write_scratch(dst, goroot_text(src))
    AT.js("""
        LocalFileSystem.getInstance().refreshAndFindFileByPath(ROOT).refresh(false, true)
        var t = 0
        while (t++ < 600 && DumbService.getInstance(project).isDumb()) sleep(100)
        true
    """)
    # the editors of the functional scenario (and of earlier runs) would be re-highlighted alongside: close them all
    AT.js("edt(function () { var fem = FileEditorManager.getInstance(project); var fs = fem.getOpenFiles(); for (var i = 0; i < fs.length; i++) fem.closeFile(fs[i]); return true }); true")


def restore(rel, text):
    """The text of an editor file back to `text` (through the editor: no File Cache Conflict)."""
    AT.js("""
        var e = openFile(__REL__)
        if (textOf(e) != __TEXT__) setText(e, __TEXT__)
        true
    """, REL=rel, TEXT=text)


def heap(point):
    out = json.loads(AT.js("""
        var mx = java.lang.management.ManagementFactory.getMemoryMXBean()
        for (var i = 0; i < 3; i++) { java.lang.System.gc(); sleep(400) }
        var u = mx.getHeapMemoryUsage()
        JSON.stringify({ usedMb: Math.round(u.getUsed() / 1048576), committedMb: Math.round(u.getCommitted() / 1048576), maxMb: Math.round(u.getMax() / 1048576) })
    """))
    HEAP[point] = out
    print("       heap %-12s used %s MB (committed %s MB)" % (point, out["usedMb"], out["committedMb"]))


def meta(flags):
    out = json.loads(AT.js("""
        var info = com.intellij.openapi.application.ApplicationInfo.getInstance()
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("io.github.golangsupport"))
        JSON.stringify({ ide: String(info.getFullApplicationName()) + " " + String(info.getBuild().asString()), plugin: String(plugin.getVersion()),
                         cpus: java.lang.Runtime.getRuntime().availableProcessors(), xmxMb: Math.round(java.lang.Runtime.getRuntime().maxMemory() / 1048576),
                         autoReparseDelayMs: com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings.getInstance().getAutoReparseDelay(),
                         java: String(java.lang.System.getProperty("java.runtime.version")) })
    """))
    go_version = ""
    try:
        go_version = subprocess.run([os.path.join(AT.GOROOT, "bin", "go.exe" if os.name == "nt" else "go"), "version"],
                                    capture_output=True, text=True, timeout=30, check=False).stdout.strip()
    except Exception:  # noqa: BLE001, S110 - the go version is informational
        pass
    META.update(out)
    META.update(date=time.strftime("%Y-%m-%d %H:%M"), flags=flags, go=go_version, host=platform.platform(), goroot=AT.GOROOT)


# ------------------------------------------------------------------------------------------------------------------------------
# P1 typing


def p1_typing():
    original = goroot_text("net/http/server.go")
    restore("httpsrv/server.go", original)
    daemon, start, run_ms, edt_ms, rt_ms, stalls, annot, restarts, sigs = [], [], [], [], [], [], [], [], []
    for k in range(P1_KEYS):
        r = jsj("""
            var e = openFile("httpsrv/server.go")
            quiet(e)
            var fs = bodies(textOf(e))
            var f = fs[Math.floor(__K__ * fs.length / __N__) + 1]
            // a statement of our own as the first line of the body, the caret after its `1`; then a quiet daemon
            write(function () { e.getDocument().insertString(f.at, "\\t_ = 1\\n") })
            edt(function () { e.getCaretModel().moveToOffset(f.at + 6); e.getScrollingModel().scrollToCaret(ScrollType.CENTER); focus(e); return true })
            quiet(e)
            var key = typeKey(e, "2")
            var d = awaitDaemon(e, key.t, 120000)
            d.edtMs = key.edtMs; d.roundtripMs = key.roundtripMs; d.sig = f.sig; d.line = lineText(e, caretLine(e)).trim()
            return JSON.stringify(d)
        """, K=k, N=P1_KEYS)
        daemon.append(r["ms"]), start.append(r["startMs"]), run_ms.append(r["runMs"]), edt_ms.append(r["edtMs"])
        rt_ms.append(r["roundtripMs"]), stalls.append(r["maxStallMs"]), annot.append(r["annotMs"]), restarts.append(r["restarts"])
        sigs.append(r["sig"])
        if r["line"] != "_ = 12":
            check("P1", "keystroke %d typed `_ = 12` (got %r in %s)" % (k, r["line"], r["sig"]), False)
    put("P1.key_to_daemon_finished", daemon, "one keystroke (`_ = 1` -> `_ = 12`, %d different functions) until daemonFinished" % P1_KEYS)
    put("P1.key_to_daemon_start", start, "keystroke until the daemon started (autoreparse delay + commit)")
    put("P1.daemon_run", run_ms, "daemonStarting -> daemonFinished of the run that finished")
    put("P1.annotator_gopsi", annot, "GoSemanticHighlightingAnnotator in that run (platform annotator statistics)")
    put("P1.key_edt", edt_ms, "the typed-action handler on the EDT (document change, typed handlers)")
    put("P1.key_roundtrip", rt_ms, "keystroke posted from the robot thread until the handler returned (EDT queue wait + handler)")
    put("P1.edt_stall_max", stalls, "longest EDT round trip while waiting for the daemon (UI freeze during highlighting)")
    put("P1.daemon_restarts", restarts, "daemonStarting events per keystroke", unit="count")
    note("P1", "functions: " + "; ".join(sigs[:6]) + "; ...")

    # a burst of 10 characters 60 ms apart (as step 14 does with 20)
    burst, burst_edt = [], []
    for k in range(REPEAT):
        r = jsj("""
            var e = openFile("httpsrv/server.go")
            quiet(e)
            var fs = bodies(textOf(e))
            var f = fs[Math.floor((__K__ + 0.5) * fs.length / __N__)]
            write(function () { e.getDocument().insertString(f.at, "\\t_ = 1\\n") })
            edt(function () { e.getCaretModel().moveToOffset(f.at + 6); focus(e); return true })
            quiet(e)
            var chars = "2345678901", keys = [], last = null
            for (var i = 0; i < chars.length; i++) { last = typeKey(e, chars.charAt(i)); keys.push(last.edtMs); sleep(60) }
            var d = awaitDaemon(e, last.t, 120000)
            keys.sort(function (a, b) { return a - b })
            d.keyMax = keys[keys.length - 1]
            d.line = lineText(e, caretLine(e)).trim()
            return JSON.stringify(d)
        """, K=k, N=REPEAT)
        burst.append(r["ms"]), burst_edt.append(r["keyMax"])
        check("P1", "burst %d typed `_ = 12345678901` (%s)" % (k, r["line"]), r["line"] == "_ = 12345678901")
    put("P1.burst10_last_key_to_daemon", burst, "10 keys 60 ms apart; last key until daemonFinished")
    put("P1.burst10_key_edt_max", burst_edt, "slowest typed-action handler of the burst")

    # attribution: the go-psi parts of a keystroke measured alone, with the daemon timer off
    commit, check_cold, check_warm, ndiag = [], [], [], []
    for k in range(REPEAT):
        r = jsj("""
            var e = openFile("httpsrv/server.go")
            quiet(e)
            var svc = project.getService(cls("io.github.golangsupport.semantic.api.GoSemanticService"))
            var d = DaemonCodeAnalyzer.getInstance(project)
            var res = {}
            edt(function () { d.setUpdateByTimerEnabled(false); return true })
            try {
                var fs = bodies(textOf(e))
                var f = fs[Math.floor((__K__ + 0.25) * fs.length / __N__)]
                res.commitMs = edt(function () {
                    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { e.getDocument().insertString(f.at, "\\t_ = 1\\n") } }))
                    var a = nano()
                    PsiDocumentManager.getInstance(project).commitDocument(e.getDocument())
                    return nano() - a
                })
                var psi = psiOf(e)
                var a = nano()
                res.diagnostics = read(function () { return svc.check(psi).size() })
                res.checkColdMs = nano() - a
                a = nano()
                read(function () { return svc.check(psi).size() })
                res.checkWarmMs = nano() - a
            } finally {
                edt(function () { d.setUpdateByTimerEnabled(true); d.restart(); return true })
            }
            return JSON.stringify(res)
        """, K=k, N=REPEAT)
        commit.append(r["commitMs"]), check_cold.append(r["checkColdMs"]), check_warm.append(r["checkWarmMs"]), ndiag.append(r["diagnostics"])
    put("P1.attr_commit_reparse", commit, "commitDocument after inserting a line (go-psi lexer/parser reparse + PSI events), EDT")
    put("P1.attr_check_after_edit", check_cold, "GoSemanticService.check(server.go) right after the edit (what the inspections wait for)")
    put("P1.attr_check_warm", check_warm, "the same check again, nothing changed (caches warm)")
    note("P1", "server.go copy in package httpsrv (with only perfextra.go of P8): check() reports %s diagnostics (the other net/http files are missing)" % ndiag[-1])
    restore("httpsrv/server.go", original)
    check("P1", "all %d single keystrokes measured" % P1_KEYS, PERF["P1.key_to_daemon_finished"]["failed"] == 0)


# ------------------------------------------------------------------------------------------------------------------------------
# P2 top-level edit, open file B


def p2_cross_file():
    restore("perfpkg/a.go", PERFPKG_A)
    base = jsj("""
        var eb = openFile("perfpkg/b.go")
        quiet(eb)
        return JSON.stringify({ problems: problems(eb) })
    """)
    check("P2", "b.go clean before the edits (%s)" % base["problems"], not base["problems"])
    add, revert, add_start = [], [], []
    for k in range(REPEAT):
        for frm, to, want, bucket in ((SIG_OLD, SIG_NEW, True, add), (SIG_NEW, SIG_OLD, False, revert)):
            r = jsj("""
                var eb = openFile("perfpkg/b.go")
                quiet(eb)
                var docA = read(function () { return FileDocumentManager.getInstance().getDocument(vf("perfpkg/a.go")) })
                var from = __FROM__, to = __TO__, want = __WANT__
                var at = String(read(function () { return docA.getText() })).indexOf(from)
                if (at < 0) throw new java.lang.IllegalStateException("no " + from + " in a.go")
                function arity() {
                    var list = infos(eb, HighlightSeverity.ERROR), n = 0
                    for (var i = 0; i < list.length; i++) if (String(list[i].getDescription()).indexOf("not enough arguments") >= 0) n++
                    return n
                }
                var t = edt(function () {
                    var a = nano()
                    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { docA.replaceString(at, at + from.length, to) } }))
                    return a
                })
                var d = awaitDaemon(eb, t, 60000, function () { return (arity() > 0) == want })
                d.errors = arity()
                d.problems = problems(eb)
                return JSON.stringify(d)
            """, FROM=frm, TO=to, WANT=want)
            bucket.append(r["ms"])
            if want:
                add_start.append(r["startMs"])
            if r["ms"] < 0 or (r["errors"] > 0) != want:
                check("P2", "edit %d (%s): b.go %s the arity errors (%s)" % (k, "add parameter" if want else "revert", "shows" if want else "lost", r["problems"]), False)
        if k == 0:
            note("P2", "b.go after adding the parameter: errors on every `Compute(x, y)` call (`not enough arguments in call to Compute`)")
    put("P2.add_param_to_b_updated", add, "a.go (not shown) gets a 3rd parameter -> b.go's daemon finished with the `not enough arguments` errors")
    put("P2.add_param_to_b_daemon_start", add_start, "the same edit until the daemon started on b.go")
    put("P2.revert_to_b_clean", revert, "the parameter removed again -> b.go's daemon finished and the errors are gone")
    check("P2", "every edit reached b.go (%d + %d of %d)" % (PERF["P2.add_param_to_b_updated"]["n"], PERF["P2.revert_to_b_clean"]["n"], 2 * REPEAT),
          PERF["P2.add_param_to_b_updated"]["failed"] == 0 and PERF["P2.revert_to_b_clean"]["failed"] == 0)
    restore("perfpkg/a.go", PERFPKG_A)


# ------------------------------------------------------------------------------------------------------------------------------
# P3 completion

COMPLETE_JS = r"""
var e = openFile("perfcompl/compl.go")
var want = __TEXT__
if (textOf(e) != want) setText(e, want)
quiet(e)
caretTo(e, "\t" + __LINE__ + "\n", 1 + __LINE__.length)
sleep(200)
var res = null, attempts = 0
for (var attempt = 0; attempt < 3 && res == null; attempt++) {
    attempts++
    var r = edt(function () {
        focus(e)
        var a = nano()
        new com.intellij.codeInsight.completion.CodeCompletionHandlerBase(com.intellij.codeInsight.completion.CompletionType.BASIC, true, false, true).invokeCompletion(project, e)
        return [a, nano() - a]
    })
    for (var t = 0; t < 600 && res == null; t++) {
        var got = edt(function () {
            var lookup = com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(e)
            if (lookup == null || !lookup.isShown()) return null
            var list = lookup.getItems(), names = []
            for (var i = 0; i < list.size() && i < 200; i++) names.push(String(list.get(i).getLookupString()))
            return names
        })
        if (got != null && got.length > 0) res = { ms: nano() - r[0], edtMs: r[1], count: got.length, items: got.slice(0, 40), attempts: attempts }
        else sleep(5)
    }
}
closePopups()
if (textOf(e) != want) setText(e, want)
return JSON.stringify(res || { ms: -1, edtMs: -1, count: 0, items: [], attempts: attempts })
"""


def p3_completion(cold_possible):
    cases = (("member", "r.", ("URL", "Header", "Context")), ("statement", "", ("w", "r", "handle")))
    for name, line, expected in cases:
        text = COMPL_FILE.replace("\t//CARET", "\t" + line)
        samples, edt_samples, counts, first = [], [], [], None
        for k in range(REPEAT + 1):
            r = jsj(COMPLETE_JS, TEXT=text, LINE=line)
            if k == 0:
                first = r
            else:
                samples.append(r["ms"]), edt_samples.append(r["edtMs"]), counts.append(r["count"])
            if r["ms"] < 0 or not all(x in r["items"] for x in expected):
                check("P3", "%s completion %d lists %s (got %d items: %s, attempts %d)" % (name, k, ", ".join(expected), r["count"], r["items"][:12], r["attempts"]), False)
        label = "first in the IDE session" if (cold_possible and name == "member") else "first of this case"
        put("P3.%s_first" % name, [first["ms"]], "`%s<caret>` -> lookup shown with items, %s (%d items)" % (line, label, first["count"]))
        put("P3.%s_warm" % name, samples, "`%s<caret>` -> lookup shown with items, repeated" % line)
        put("P3.%s_warm_edt" % name, edt_samples, "the EDT part of the invocation (synchronous wait of the action included)")
        note("P3", "%s: %d items, first: %s" % (name, counts[-1] if counts else 0, ", ".join(first["items"][:10])))
    if not cold_possible:
        note("P3", "not cold: the functional scenario or an earlier --attach run used completion in this IDE session")
    check("P3", "all completions showed the expected items", not any(c for c in CHECKS if c[0] == "P3" and not c[2]))


# ------------------------------------------------------------------------------------------------------------------------------
# P4 navigation, usages, documentation

GOTO_JS = r"""
var e = openFile("httpsrv/server.go")
quiet(e)
var from = String(selectedState()[0])
caretTo(e, __NEEDLE__, __DELTA__)
sleep(150)
var line0 = selectedState()[1]
var a = nano()
invoke("GotoDeclaration", e)
var ms = -1, where = null
for (var t = 0; t < 3000; t++) {
    var s = selectedState()
    if (String(s[0]) != from || s[1] != line0) { ms = nano() - a; where = String(s[0]).replace(/.*[\\/]/, "") + ":" + (s[1] + 1); break }
    sleep(2)
}
var sel = selected()
var target = sel == null ? "" : lineText(sel, caretLine(sel)).trim()
return JSON.stringify({ ms: ms, where: where, target: target })
"""

USAGES_JS = r"""
var e = openFile("httpsrv/server.go")
quiet(e)
caretTo(e, __NEEDLE__, __DELTA__)
var target = read(function () { var u = com.intellij.codeInsight.TargetElementUtil.getInstance(); return u.findTargetElement(e, u.getAllAccepted(), e.getCaretModel().getOffset()) })
if (target == null) throw new java.lang.IllegalStateException("no target at " + __NEEDLE__)
var scope = __ALL__ ? GlobalSearchScope.allScope(project) : GlobalSearchScope.projectScope(project)
var a = nano()
var n = com.intellij.psi.search.searches.ReferencesSearch.search(target, scope).findAll().size()
return JSON.stringify({ ms: nano() - a, count: n })
"""

SHOW_USAGES_JS = r"""
var e = openFile("httpsrv/server.go")
quiet(e)
caretTo(e, __NEEDLE__, __DELTA__)
sleep(150)
function rows() {
    return edtAny(function () {
        var best = -1
        function walk(c) {
            if (c instanceof javax.swing.JTable && c.isShowing() && String(c.getClass().getName()).indexOf("ShowUsages") >= 0) best = Math.max(best, c.getRowCount())
            if (c instanceof java.awt.Container) { var k = c.getComponents(); for (var i = 0; i < k.length; i++) walk(k[i]) }
        }
        var ws = java.awt.Window.getWindows()
        for (var w = 0; w < ws.length; w++) if (ws[w].isShowing()) walk(ws[w])
        return best
    })
}
var a = nano()
closePopups()
invoke("ShowUsages", e)
var first = -1, lastChange = -1, prev = -1, n = -1
for (var t = 0; t < 4000; t++) {
    n = rows()
    var now_ = nano() - a
    if (n > 0 && first < 0) first = now_
    if (n != prev) { lastChange = now_; prev = n }
    if (first >= 0 && now_ - lastChange > 600) break
    if (now_ > 30000) break
    sleep(5)
}
closePopups()
return JSON.stringify({ firstRowsMs: first, settledMs: lastChange, rows: n })
"""

DOC_JS = r"""
var e = openFile("httpsrv/server.go")
quiet(e)
var offset = caretTo(e, __NEEDLE__, __DELTA__)
var psi = psiOf(e)
var a = nano()
var html = String(read(function () {
    var leaf = psi.findElementAt(offset)
    var ref = psi.findReferenceAt(offset)
    var target = ref == null ? null : ref.resolve()
    if (target == null) return "unresolved"
    var providers = com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider.EP_NAME.getExtensionList()
    for (var i = 0; i < providers.size(); i++) {
        var targets = providers.get(i).documentationTargets(target, leaf)
        if (targets != null && targets.size() > 0) { var r = targets.get(0).computeDocumentation(); return r == null ? "null" : String(r.getHtml()) }
    }
    return "no target"
}))
var computeMs = nano() - a
// the popup as Ctrl+Q shows it
function popupHas(text) {
    return edtAny(function () {
        var found = false
        function walk(c) {
            if (found) return
            if (c instanceof javax.swing.JEditorPane && c.isShowing() && String(c.getText()).replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").indexOf(text) >= 0) { found = true; return }
            if (c instanceof java.awt.Container) { var k = c.getComponents(); for (var i = 0; i < k.length; i++) walk(k[i]) }
        }
        var ws = java.awt.Window.getWindows()
        for (var w = 0; w < ws.length; w++) if (ws[w].isShowing() && !(ws[w] instanceof javax.swing.JFrame)) walk(ws[w])
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
        if (frame != null) walk(frame.getLayeredPane())
        return found
    })
}
closePopups()
caretTo(e, __NEEDLE__, __DELTA__)
var b = nano(), popupMs = -1
invoke("QuickJavaDoc", e)
for (var t = 0; t < 3000; t++) { if (popupHas(__EXPECT__)) { popupMs = nano() - b; break } sleep(5); if (nano() - b > 15000) break }
closePopups()
sleep(200)
return JSON.stringify({ computeMs: computeMs, popupMs: popupMs, hasText: html.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").indexOf(__EXPECT__) >= 0 })
"""


def p4_navigation():
    original = goroot_text("net/http/server.go")
    restore("httpsrv/server.go", original)
    targets = {
        "local": ("c.readRequest(ctx)", 3, "server.go", "func (c *conn) readRequest", "Read next request from connection"),
        "goroot": ("ctx, cancelCtx := context.WithCancel(ctx)", 27, "context.go", "func WithCancel(", "Canceling this context releases resources"),
    }
    for name, (needle, delta, target_file, target_line, doc_text) in targets.items():
        samples = []
        for k in range(REPEAT):
            r = jsj(GOTO_JS, NEEDLE=needle, DELTA=delta)
            samples.append(r["ms"])
            if r["ms"] < 0 or not (r["where"] or "").startswith(target_file) or target_line not in r["target"]:
                check("P4", "Go to Declaration %s %d reached %s (%s %s)" % (name, k, target_line, r["where"], r["target"]), False)
        put("P4.goto_%s" % name, samples, "GotoDeclaration on `%s` until the caret/editor moved to `%s`" % (needle.split(":= ")[-1], target_line))
        comp, pop = [], []
        for k in range(REPEAT):
            r = jsj(DOC_JS, NEEDLE=needle, DELTA=delta, EXPECT=doc_text)
            comp.append(r["computeMs"]), pop.append(r["popupMs"])
            if not r["hasText"] or r["popupMs"] < 0:
                check("P4", "Quick Documentation %s %d shows `%s` (computed: %s, popup %d ms)" % (name, k, doc_text, r["hasText"], r["popupMs"]), False)
        put("P4.doc_compute_%s" % name, comp, "documentation HTML for `%s` computed in a read action (resolve + render)" % target_line)
        put("P4.doc_popup_%s" % name, pop, "QuickJavaDoc action until the popup shows the doc text")
    for name, needle, delta, all_scope in (("conn_project", "type conn struct", 5, False), ("withcancel_project", "ctx, cancelCtx := context.WithCancel(ctx)", 27, False),
                                         ("withcancel_all", "ctx, cancelCtx := context.WithCancel(ctx)", 27, True)):
        samples, count = [], 0
        for k in range(REPEAT):
            r = jsj(USAGES_JS, NEEDLE=needle, DELTA=delta, ALL=all_scope)
            samples.append(r["ms"])
            count = r["count"]
        what = {"conn_project": "type `conn` (server.go)", "withcancel_project": "context.WithCancel (GOROOT)", "withcancel_all": "context.WithCancel (GOROOT)"}[name]
        put("P4.usages_%s" % name, samples, "ReferencesSearch for %s, %s scope: %d usages" % (what, "project + libraries" if all_scope else "project", count))
        check("P4", "usages of %s found (%d)" % (what, count), count > 0)
    first, settled, rows = [], [], 0
    for k in range(REPEAT):
        r = jsj(SHOW_USAGES_JS, NEEDLE="type conn struct", DELTA=5)
        first.append(r["firstRowsMs"]), settled.append(r["settledMs"])
        rows = r["rows"]
    put("P4.show_usages_first_rows", first, "ShowUsages popup on type `conn` until its table has rows")
    put("P4.show_usages_settled", settled, "the same until the row count stopped changing (%d rows; 600 ms of no change not counted)" % rows)
    check("P4", "Show Usages popup filled (%d rows)" % rows, rows > 0)
    restore("httpsrv/server.go", original)


# ------------------------------------------------------------------------------------------------------------------------------
# P5 reformat

REFORMAT_JS = r"""
var e = openFile("httpsrv/server.go")
var want = __ORIGINAL__
var start = __START__
if (textOf(e) != start) setText(e, start)
quiet(e)
var psi = psiOf(e)
var ms = edt(function () {
    var a = nano()
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
        com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(psi)
    } }))
    return nano() - a
})
var same = textOf(e) == want
return JSON.stringify({ ms: ms, same: same })
"""

REFORMAT_ACTION_JS = r"""
var e = openFile("httpsrv/server.go")
var want = __ORIGINAL__
var start = __START__
if (textOf(e) != start) setText(e, start)
quiet(e)
var a = nano(), ms = -1
invoke("ReformatCode", e)
for (var t = 0; t < 6000; t++) { if (textOf(e) == want) { ms = nano() - a; break } sleep(5); if (nano() - a > 60000) break }
return JSON.stringify({ ms: ms })
"""


def p5_reformat():
    original = goroot_text("net/http/server.go")
    flat = "\n".join(l.lstrip("\t") for l in original.split("\n"))
    noop, heavy, action = [], [], []
    for k in range(REPEAT):
        r = jsj(REFORMAT_JS, ORIGINAL=original, START=original)
        noop.append(r["ms"])
        if not r["same"]:
            check("P5", "reformat %d of the gofmt-formatted file changes nothing" % k, False)
    for k in range(REPEAT):
        r = jsj(REFORMAT_JS, ORIGINAL=original, START=flat)
        heavy.append(r["ms"])
        if not r["same"]:
            check("P5", "reformat %d of the unindented file gives gofmt's text back" % k, False)
    for k in range(REPEAT):
        r = jsj(REFORMAT_ACTION_JS, ORIGINAL=original, START=flat)
        action.append(r["ms"])
    put("P5.reformat_formatted", noop, "CodeStyleManager.reformat(server.go) in a write command, file already gofmt-formatted (4292 lines)")
    put("P5.reformat_unindented", heavy, "the same on the file with every leading tab removed (result == original)")
    put("P5.reformat_action_unindented", action, "ReformatCode action on the unindented file until the text equals the original")
    check("P5", "all reformats measured and identical to gofmt", not any(c for c in CHECKS if c[0] == "P5" and not c[2]) and PERF["P5.reformat_action_unindented"]["failed"] == 0)
    restore("httpsrv/server.go", original)


# ------------------------------------------------------------------------------------------------------------------------------
# P6 opening large files

OPEN_JS = r"""
var f = vf(__REL__)
var fem = FileEditorManager.getInstance(project)
if (__CLOSE__) { edt(function () { fem.closeFile(f); return true }); sleep(300) }
var a = 0, openMs = 0
var e = edt(function () {
    a = nano()
    var ed = fem.openTextEditor(new OpenFileDescriptor(project, f, 0, 0), true)
    openMs = nano() - a
    return ed
})
var d = awaitDaemon(e, a, 180000)
d.openMs = openMs
d.lines = read(function () { return e.getDocument().getLineCount() })
d.problems = problems(e).length
return JSON.stringify(d)
"""


def p6_open():
    first, first_open, annot, lines = [], [], [], []
    for src, dst in OPEN_FILES:
        r = jsj(OPEN_JS, REL=dst, CLOSE=False)
        first.append(r["ms"]), first_open.append(r["openMs"]), annot.append(r["annotMs"])
        lines.append("%s %d lines, %d problems shown, %.0f ms" % (src, r["lines"], r["problems"], r["ms"]))
    put("P6.first_open_to_highlighted", first, "a GOROOT file never opened before (5 different files, copied into own dirs): open until daemonFinished")
    put("P6.first_open_edt", first_open, "openTextEditor on the EDT (editor creation, first PSI/stub->AST load)")
    put("P6.first_open_annotator_gopsi", annot, "GoSemanticHighlightingAnnotator in that first run")
    note("P6", "files: " + "; ".join(lines))
    again = []
    for k in range(REPEAT):
        r = jsj(OPEN_JS, REL=OPEN_FILES[0][1], CLOSE=True)
        again.append(r["ms"])
    put("P6.reopen_expr_go", again, "go/types/expr.go closed and opened again: open until daemonFinished")
    check("P6", "every open was highlighted", PERF["P6.first_open_to_highlighted"]["failed"] == 0 and PERF["P6.reopen_expr_go"]["failed"] == 0)


# ------------------------------------------------------------------------------------------------------------------------------
# P8 declaration edits and edits in a sibling file, the big file open

PERF_EXTRA = """package http

// perfExtra is edited by the UI robot's performance mode (P8); nothing in server.go uses it.
func perfExtra(a int) int {
\treturn a + 1
}
"""

# (what, from, to) in httpsrv/perfextra.go; each edit is reverted right after it was measured
SIBLING_EDITS = (
    ("body", "\treturn a + 1\n", "\treturn a + 2\n"),
    ("decl", "func perfExtra(a int) int {", "func perfExtra(a int, b int) int {"),
)

DECL_KEY_JS = r"""
var e = openFile("httpsrv/server.go")
quiet(e)
var end = read(function () { return e.getDocument().getTextLength() })
// a top-level declaration of our own at the end of the file, the caret after its `1`; then a quiet daemon
write(function () { e.getDocument().insertString(end, "\nvar perfV = 1\n") })
edt(function () { e.getCaretModel().moveToOffset(end + 14); e.getScrollingModel().scrollToCaret(ScrollType.CENTER); focus(e); return true })
quiet(e)
var key = typeKey(e, "2")
var d = awaitDaemon(e, key.t, 120000)
d.edtMs = key.edtMs
d.line = lineText(e, caretLine(e)).trim()
return JSON.stringify(d)
"""

SIBLING_JS = r"""
var e = openFile("httpsrv/server.go")
quiet(e)
var doc = read(function () { return FileDocumentManager.getInstance().getDocument(vf("httpsrv/perfextra.go")) })
function edit(from, to) {
    var at = String(read(function () { return doc.getText() })).indexOf(from)
    if (at < 0) throw new java.lang.IllegalStateException("no " + from + " in perfextra.go")
    return edt(function () {
        var a = nano()
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { doc.replaceString(at, at + from.length, to) } }))
        return a
    })
}
var t = edit(__FROM__, __TO__)
var d = awaitDaemon(e, t, 120000, null, 3000)
edit(__TO__, __FROM__)
quiet(e)
return JSON.stringify(d)
"""

# with the daemon timer off: what GoSemanticService.check(server.go) costs after each kind of edit (which caches survive it)
ATTR_EDITS_JS = r"""
var e = openFile("httpsrv/server.go")
quiet(e)
var svc = project.getService(cls("io.github.golangsupport.semantic.api.GoSemanticService"))
var d = DaemonCodeAnalyzer.getInstance(project)
var docX = read(function () { return FileDocumentManager.getInstance().getDocument(vf("httpsrv/perfextra.go")) })
var docS = e.getDocument()
var psi = psiOf(e)
function change(doc, from, to) {
    edt(function () {
        var text = String(doc.getText())
        var at = from == null ? text.length : text.indexOf(from)
        if (at < 0) throw new java.lang.IllegalStateException("not in the text: " + from)
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
            if (from == null) doc.insertString(at, to); else doc.replaceString(at, at + from.length, to)
        } }))
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        return true
    })
}
function timeCheck() { var a = nano(); read(function () { return svc.check(psi).size() }); return nano() - a }
var res = {}
edt(function () { d.setUpdateByTimerEnabled(false); return true })
try {
    timeCheck()
    res.warm = timeCheck()
    change(docX, "\treturn a + 1\n", "\treturn a + 2\n")
    res.siblingBody = timeCheck()
    change(docX, "\treturn a + 2\n", "\treturn a + 1\n")
    timeCheck()
    change(docX, "func perfExtra(a int) int {", "func perfExtra(a int, b int) int {")
    res.siblingDecl = timeCheck()
    change(docX, "func perfExtra(a int, b int) int {", "func perfExtra(a int) int {")
    timeCheck()
    change(docS, null, "\nvar perfV = 1\n")
    res.ownDecl = timeCheck()
    change(docS, "\nvar perfV = 1\n", "")
    timeCheck()
} finally {
    edt(function () { d.setUpdateByTimerEnabled(true); d.restart(); return true })
}
return JSON.stringify(res)
"""


def p8_decl_and_sibling():
    original = goroot_text("net/http/server.go")
    daemon, annot, edt_ms = [], [], []
    for k in range(REPEAT):
        restore("httpsrv/server.go", original)
        r = jsj(DECL_KEY_JS)
        daemon.append(r["ms"]), annot.append(r["annotMs"]), edt_ms.append(r["edtMs"])
        if r["line"] != "var perfV = 12":
            check("P8", "keystroke %d typed `var perfV = 12` (got %r)" % (k, r["line"]), False)
    restore("httpsrv/server.go", original)
    put("P8.decl_key_to_daemon_finished", daemon, "one keystroke in a top-level `var` at the end of server.go (outside any body) until daemonFinished")
    put("P8.decl_key_annotator_gopsi", annot, "GoSemanticHighlightingAnnotator in that run")
    put("P8.decl_key_edt", edt_ms, "the typed-action handler on the EDT")
    for what, frm, to in SIBLING_EDITS:
        daemon, annot, runs = [], [], []
        for k in range(REPEAT):
            r = jsj(SIBLING_JS, FROM=frm, TO=to)
            daemon.append(r["ms"]), annot.append(r["annotMs"]), runs.append(r["restarts"])
        label = {"body": "a function body", "decl": "a function signature"}[what]
        put("P8.sibling_%s_to_server_finished" % what, daemon,
            "%s in httpsrv/perfextra.go (same package, not shown) changed -> server.go's daemon finished (0: it did not run on server.go)" % label)
        put("P8.sibling_%s_annotator_gopsi" % what, annot, "GoSemanticHighlightingAnnotator on server.go in that run")
        put("P8.sibling_%s_server_runs" % what, runs, "daemonStarting events on server.go per edit", unit="count")
    warm, body, decl, own = [], [], [], []
    for k in range(REPEAT):
        r = jsj(ATTR_EDITS_JS)
        warm.append(r["warm"]), body.append(r["siblingBody"]), decl.append(r["siblingDecl"]), own.append(r["ownDecl"])
    put("P8.attr_check_warm", warm, "GoSemanticService.check(server.go), nothing changed (daemon timer off)")
    put("P8.attr_check_after_sibling_body", body, "the same check right after a body edit in perfextra.go")
    put("P8.attr_check_after_sibling_decl", decl, "the same check right after a signature edit in perfextra.go")
    put("P8.attr_check_after_own_decl", own, "the same check right after a top-level `var` was added to server.go")
    restore("httpsrv/server.go", original)
    restore("httpsrv/perfextra.go", PERF_EXTRA)
    check("P8", "every P8 edit was measured", all(PERF[k]["failed"] == 0 for k in PERF if k.startswith("P8.")))


# ------------------------------------------------------------------------------------------------------------------------------
# P9 memory retained by the semantic caches

MEM_FILES = [
    ("go/types/stmt.go", "mem/typesstmt/stmt.go"),
    ("go/types/decl.go", "mem/typesdecl/decl.go"),
    ("go/printer/nodes.go", "mem/printer/nodes.go"),
    ("encoding/json/decode.go", "mem/json/decode.go"),
    ("text/template/exec.go", "mem/template/exec.go"),
    ("net/http/request.go", "mem/httpreq/request.go"),
    ("cmd/compile/internal/ssagen/ssa.go", "mem/ssagen/ssa.go"),
]

MEMORY_JS = r"""
// files never analysed in this IDE session (fresh copies): ASTs loaded and held, then GoSemanticService.check on each.
// Used heap after GC: before, with the ASTs held, with the ASTs held and the caches check() filled.
edt(function () { var fem = FileEditorManager.getInstance(project); var fs = fem.getOpenFiles(); for (var i = 0; i < fs.length; i++) fem.closeFile(fs[i]); return true })
var svc = project.getService(cls("io.github.golangsupport.semantic.api.GoSemanticService"))
var mx = java.lang.management.ManagementFactory.getMemoryMXBean()
function usedMb() {
    for (var i = 0; i < 3; i++) { java.lang.System.gc(); sleep(300) }
    return mx.getHeapMemoryUsage().getUsed() / 1048576
}
var rels = __RELS__, files = [], holds = []
for (var i = 0; i < rels.length; i++) { var f = vf(rels[i]); files.push(read(function () { return com.intellij.psi.PsiManager.getInstance(project).findFile(f) })) }
for (var i = 0; i < 100 && edt(function () { return DaemonCodeAnalyzer.getInstance(project).isRunningOrPending() }); i++) sleep(100)
sleep(1000)
var h0 = usedMb()
var lines = 0, elements = 0
for (var i = 0; i < files.length; i++) {
    var f = files[i]
    holds.push(read(function () {
        elements += com.intellij.psi.SyntaxTraverser.psiTraverser(f).traverse().size()
        lines += com.intellij.openapi.util.text.StringUtil.countNewLines(f.getViewProvider().getContents())
        return f.getNode()
    }))
}
var h1 = usedMb()
var checks = [], diags = 0
for (var i = 0; i < files.length; i++) {
    var f = files[i]
    var a = nano()
    diags += read(function () { return svc.check(f).size() })
    checks.push(nano() - a)
}
var h2 = usedMb()
var warm = []
for (var i = 0; i < files.length; i++) { var f = files[i]; var a = nano(); read(function () { return svc.check(f).size() }); warm.push(nano() - a) }
var res = { astMb: h1 - h0, cacheMb: h2 - h1, baseMb: h0, checkMs: checks, warmMs: warm, lines: lines, elements: elements, diagnostics: diags, held: holds.length }
holds = null
return JSON.stringify(res)
"""


def p9_memory():
    r = jsj(MEMORY_JS, RELS=[dst for _, dst in MEM_FILES], timeout=900)
    kloc = r["lines"] / 1000.0
    put("P9.cache_retained_mb", [r["cacheMb"]], "used heap after GC with the ASTs held and check() run on %d fresh files (%d lines), minus with the ASTs held only: "
        "the semantic caches plus the library PSI/stubs check() loaded" % (len(MEM_FILES), r["lines"]), unit="MB")
    put("P9.cache_retained_mb_per_kloc", [r["cacheMb"] / kloc if kloc else -1], "the same per 1000 lines", unit="MB")
    put("P9.ast_mb", [r["astMb"]], "used heap after GC with those files' ASTs held (%d PSI elements), minus before" % r["elements"], unit="MB")
    put("P9.check_cold", r["checkMs"], "GoSemanticService.check of a file never analysed before (AST loaded), per file")
    put("P9.check_cold_total", [sum(r["checkMs"])], "the sum over the %d files" % len(MEM_FILES))
    put("P9.check_warm", r["warmMs"], "the same check again, per file (caches warm)")
    note("P9", "files: %s; %d diagnostics in total (each file alone in its package); used heap before: %.0f MB. "
               "Single GC-based samples, a few MB of noise" % (", ".join(src for src, _ in MEM_FILES), r["diagnostics"], r["baseMb"]))
    check("P9", "the ASTs stayed loaded and every check ran", r["held"] == len(MEM_FILES) and len(r["checkMs"]) == len(MEM_FILES))


# ------------------------------------------------------------------------------------------------------------------------------
# P7 indexing: project open until smart mode with GOROOT indexed

OPEN_WAIT_JS = r"""
// t0 = the open call. Polls every 100 ms. `firstSmartMs`: the project is open, initialized and not dumb for the first time.
// `gorootIndexedMs`: the last dumb -> smart transition before 5 s of uninterrupted smart mode, with $GOROOT/src/fmt/print.go
// in a library of the project (GOROOT roots registered). Index queries are no criterion: right after a start the platform
// answers them for files that are not scanned yet, and scanning runs partly outside dumb mode (gaps of ~1 s).
var marker = "ROBOT PERF MARK " + __TAG__
com.intellij.openapi.diagnostic.Logger.getInstance("gopsi.robot.perf").info(marker)
var goroot = String(__GOROOT__).replace(/\\/g, "/")
var printGo = LocalFileSystem.getInstance().refreshAndFindFileByPath(goroot + "/src/fmt/print.go")
var t0 = now()
later(function () { com.intellij.ide.impl.ProjectUtil.openOrImport(ROOT, null, true) })
var p = null, firstSmart = -1, indexed = -1, smartSince = -1, transitions = 0
while (now() - t0 < 900000) {
    sleep(100)
    var ps = ProjectManager.getInstance().getOpenProjects()
    p = null
    for (var i = 0; i < ps.length; i++) if (String(ps[i].getBasePath()) == ROOT) p = ps[i]
    if (p == null || !p.isInitialized()) continue
    if (DumbService.getInstance(p).isDumb()) { smartSince = -1; continue }
    var t = now() - t0
    if (firstSmart < 0) firstSmart = t
    if (smartSince < 0) { smartSince = t; transitions++ }
    var pp = p
    var inLib = read(function () { return printGo != null && com.intellij.openapi.roots.ProjectFileIndex.getInstance(pp).isInLibrary(printGo) })
    if (!inLib) { smartSince = -1; continue }
    if (t - smartSince >= 5000) { indexed = smartSince; break }
}
// then 10 s more: does the project go dumb again (a late project-model sync, a second scan)?
var laterDumb = 0, w0 = now()
if (indexed > 0) while (now() - w0 < 10000) { sleep(100); if (p != null && DumbService.getInstance(p).isDumb()) laterDumb += 100 }
JSON.stringify({ firstSmartMs: firstSmart, gorootIndexedMs: indexed, laterDumbMs: laterDumb, smartTransitions: transitions, marker: marker })
"""

CLOSE_JS = r"""
edt(function () { var fem = FileEditorManager.getInstance(project); var fs = fem.getOpenFiles(); for (var i = 0; i < fs.length; i++) fem.closeFile(fs[i]); return true })
later(function () { com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().closeAndDispose(project) })
var t = 0
while (t < 300 && ProjectManager.getInstance().getOpenProjects().length > 0) { sleep(100); t++ }
ProjectManager.getInstance().getOpenProjects().length
"""

LOG_TS = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d),(\d{3}) ")


def log_after(marker):
    """idea.log lines after the marker line (the open call), as (seconds since the marker, text)."""
    with open(AT.SANDBOX_LOG, encoding="utf-8", errors="replace") as f:
        text = f.read()
    at = text.rfind(marker)
    if at < 0:
        return []
    out, t0 = [], None
    for line in text[text.rfind("\n", 0, at) + 1:].splitlines():
        m = LOG_TS.match(line)
        if not m:
            continue
        ts = time.mktime(time.strptime(m.group(1), "%Y-%m-%d %H:%M:%S")) + int(m.group(2)) / 1000.0
        if t0 is None:
            t0 = ts
        out.append((ts - t0, line))
    return out


def indexing_from_log(marker, until_s):
    """What idea.log says between the open call and `until_s` seconds after it, for the scratch project."""
    name = os.path.basename(AT.SCRATCH)
    lines = [(t, l) for t, l in log_after(marker) if t <= until_s + 1.0]
    scans, indexers, dumb_ms, entered = [], [], 0.0, None
    for t, l in lines:
        if "enter dumb mode [%s]" % name in l:
            entered = t
        elif "exit dumb mode [%s]" % name in l and entered is not None:
            dumb_ms += (t - entered) * 1000
            entered = None
        m = re.search(r"Started scanning for indexing of \[%s\]\. Reason: (.*)" % re.escape(name), l)
        if m:
            scans.append({"at_s": round(t, 2), "reason": m.group(1)[:80]})
        m = re.search(r"Scanning completed for \[%s\]\. Number of scanned files: (\d+); number of files for indexing: (\d+)" % re.escape(name), l)
        if m and scans:
            scans[-1].update(done_s=round(t, 2), scanned=int(m.group(1)), toIndex=int(m.group(2)))
        m = re.search(r"Finished for %s\. Unindexed files update took (\d+)ms" % re.escape(name), l)
        if m:
            indexers.append({"done_s": round(t, 2), "ms": int(m.group(1))})
    return {"scans": scans, "indexers": indexers, "dumbMs": round(dumb_ms)}


def measure_open(at, tag):
    """Opens the scratch project (closed now) and measures the time to smart mode with GOROOT indexed. Returns the result."""
    global AT
    AT = at
    r = json.loads(AT.js(OPEN_WAIT_JS, timeout=960, TAG=tag, GOROOT=AT.GOROOT))
    until = (r["gorootIndexedMs"] if r["gorootIndexedMs"] > 0 else 900000) / 1000.0
    r["log"] = indexing_from_log(r["marker"], until)
    r["logAfter"] = indexing_from_log(r["marker"], until + 10.0)
    return r


def put_open(prefix, results, label):
    first = [r["firstSmartMs"] for r in results]
    full = [r["gorootIndexedMs"] for r in results]
    dumb = [r["log"]["dumbMs"] for r in results]
    idx = [sum(i["ms"] for i in r["log"]["indexers"]) for r in results]
    to_index = [sum(s.get("toIndex", 0) for s in r["log"]["scans"]) for r in results]
    put(prefix + "_first_smart", first, "%s: open call until the project is first in smart mode (project files scanned)" % label)
    put(prefix + "_goroot_indexed", full, "%s: open call until the last switch to smart mode that was followed by 5 s of smart mode, GOROOT in the libraries" % label)
    put(prefix + "_dumb_total", dumb, "%s: sum of enter->exit dumb mode in idea.log over that interval" % label)
    put(prefix + "_indexer_total", idx, "%s: sum of `Unindexed files update took` in idea.log over that interval" % label)
    put(prefix + "_files_to_index", to_index, "%s: `number of files for indexing` summed over the scans" % label, unit="files")
    put(prefix + "_dumb_in_next_10s", [r.get("laterDumbMs", -1) for r in results],
        "%s: time in dumb mode during the 10 s after that (late rescans; polled every 100 ms)" % label)


def p7_reopen():
    results = []
    for k in range(REPEAT):
        n = AT.js(CLOSE_JS)
        if n != "0":
            check("P7", "project closed before reopen %d (%s open)" % (k, n), False)
            break
        time.sleep(1)
        results.append(measure_open(AT, "reopen-%d-%d" % (k, int(time.time()))))
    if results:
        put_open("P7.warm_reopen", results, "close + reopen in the same IDE session")
        r = results[-1]["logAfter"]
        note("P7", "warm reopen (last, log until 10 s after the criterion): scans %s; indexers %s" % (json.dumps(r["scans"]), json.dumps(r["indexers"])))
    check("P7", "every reopen reached smart mode with GOROOT indexed", results and all(r["gorootIndexedMs"] > 0 for r in results))


# ------------------------------------------------------------------------------------------------------------------------------


def run(at, flags, first_open=None, cold_completion=True):
    """All P-steps; `first_open` is the measure_open() result of step 1 (None when the project was open already)."""
    global AT
    AT = at
    meta(flags)
    META["firstOpen"] = "cold (sandbox index and caches deleted before the start)" if "--cold" in flags else "first open in this IDE session"
    if first_open is not None:
        put_open("P7.first_open", [first_open], META["firstOpen"])
        note("P7", "first open (log until 10 s after the criterion): scans %s; indexers %s" % (json.dumps(first_open["logAfter"]["scans"]), json.dumps(first_open["logAfter"]["indexers"])))
        check("P7", "first open reached smart mode with GOROOT indexed", first_open["gorootIndexedMs"] > 0)
    else:
        note("P7", "the project was open already (--attach, or reopened by the IDE at startup): no first-open measurement")
    setup_files()
    heap("start")
    steps = [("P1", p1_typing), ("P2", p2_cross_file), ("P3", lambda: p3_completion(cold_completion)), ("P4", p4_navigation),
             ("P5", p5_reformat), ("P6", p6_open), ("P8", p8_decl_and_sibling), ("P9", p9_memory)]
    for step, fn in steps:
        print("perf %s %s" % (step, TITLES[step]))
        started = time.time()
        try:
            fn()
        except Exception as e:  # noqa: BLE001
            check(step, "ran without a harness error: %s" % str(e).splitlines()[0][:300], False)
            note(step, "harness error:\n```\n%s\n```" % traceback.format_exc()[-2500:])
            try:
                AT.js("escapeAll(); true")
            except Exception:  # noqa: BLE001, S110 - best effort cleanup
                pass
        note(step, "step took %.0f s" % (time.time() - started))
        finish_step(step)
        write_outputs()
    heap("before P7")
    print("perf P7 %s" % TITLES["P7"])
    try:
        p7_reopen()
    except Exception as e:  # noqa: BLE001
        check("P7", "ran without a harness error: %s" % str(e).splitlines()[0][:300], False)
        note("P7", "harness error:\n```\n%s\n```" % traceback.format_exc()[-2500:])
    finish_step("P7")
    heap("end")
    write_outputs()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    shutil.copyfile(os.path.join(AT.OUT, "perf.json"), os.path.join(AT.OUT, "perf-%s.json" % stamp))
    print("perf: %s, %s" % (os.path.join(AT.OUT, "perf.md"), os.path.join(AT.OUT, "perf.json")))


def finish_step(step):
    """A line in report.md per P-step; a screenshot only when something failed."""
    checks = [(d, ok) for s, d, ok in CHECKS if s == step]
    pictures = [] if all(ok for _, ok in checks) else [fail_shot(step)]
    keys = [k for k in PERF if k.startswith(step + ".")]
    evidence = "\n".join("%s: median %s max %s %s (n=%d) - %s" % (k, PERF[k]["median"], PERF[k]["max"], PERF[k]["unit"], PERF[k]["n"], PERF[k]["what"]) for k in keys)
    evidence += "\n" + "\n".join(NOTES.get(step, []))
    AT.record(20 + int(step[1:]), "perf %s: %s" % (step, TITLES[step]), checks or [("measured", bool(keys))], evidence, pictures)
