// Waits (up to __MAX__ s) until the last open project is out of dumb mode, no background progress runs and the daemon has finished the
// selected editor; prints how long it took and what was still running at the end.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.project.DumbService)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
var app = ApplicationManager.getApplication()
var t0 = java.lang.System.currentTimeMillis()
var quiet = 0
var last = ""
for (var tick = 0; tick < __MAX__ * 2; tick++) {
    var state = ""
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var ps = ProjectManager.getInstance().getOpenProjects()
        if (ps.length == 0) { state = "no project"; return }
        var project = ps[ps.length - 1]
        if (DumbService.isDumb(project)) state += "dumb "
        try {
            var sb = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            var infos = com.intellij.openapi.wm.ex.WindowManagerEx.getInstanceEx().getFrame(project).getStatusBar().getBackgroundProcesses()
            for (var i = 0; i < infos.size(); i++) state += "[" + infos.get(i).getFirst().getTitle() + "] "
        } catch (e) {}
        try {
            var ed = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor()
            if (ed != null) {
                var psi = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(ed.getDocument())
                var dca = com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project)
                if (psi != null && !dca.isErrorAnalyzingFinished(psi)) state += "daemon "
            }
        } catch (e) {}
    } }), ModalityState.any())
    last = state
    if (state == "") { quiet++; if (quiet >= 6) break } else quiet = 0
    java.lang.Thread.sleep(500)
}
"@@@" + "waited " + (java.lang.System.currentTimeMillis() - t0) / 1000 + " s; " + (last == "" ? "quiet" : "still: " + last) + "\n"
