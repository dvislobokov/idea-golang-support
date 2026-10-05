// Opens __FILE__, waits __WAIT__ ms, lists daemon highlight infos: severity, inspection tool id, description, range text; then editor notification panels.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var editor = null
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
} }), ModalityState.any())
java.lang.Thread.sleep(__WAIT__)
var out = new java.lang.StringBuilder("@@@")
function plain(s) { return s == null ? "" : String(s).replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/&quot;/g, "\"").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim() }
app.runReadAction(new java.lang.Runnable({ run: function () {
    var doc = editor.getDocument()
    var text = String(doc.getText())
    var infos = DaemonCodeAnalyzerImpl.getHighlights(doc, null, project)
    out.append("##### infos: " + infos.size() + "\n")
    var it = infos.iterator()
    while (it.hasNext()) {
        var info = it.next()
        if (info.getDescription() == null) continue
        var line = doc.getLineNumber(info.getStartOffset()) + 1
        out.append("  " + line + "  " + info.getSeverity().getName() + "(" + info.getSeverity().myVal + ")  [" + info.getInspectionToolId() + "]  «" + text.substring(info.getStartOffset(), Math.min(info.getEndOffset(), info.getStartOffset() + 40)).replace(/\s+/g, " ") + "»  " + plain(info.getDescription()) + "\n")
    }
} }))
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var fem = FileEditorManager.getInstance(project)
    var eds = fem.getAllEditors()
    for (var i = 0; i < eds.length; i++) {
        var comp = eds[i].getComponent()
        var panels = com.intellij.ui.ComponentUtil.findComponentsOfType(comp.getParent() == null ? comp : comp.getParent(), com.intellij.ui.EditorNotificationPanel)
        var pit = panels.iterator()
        while (pit.hasNext()) { var p = pit.next(); out.append("##### notification panel in " + eds[i].getFile().getName() + ": " + plain(p.getText()) + "\n") }
    }
} }), ModalityState.any())
String(out)
