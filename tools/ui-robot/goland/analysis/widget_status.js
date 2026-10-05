// The inspections widget (traffic light) of the selected editor: AnalyzerStatus — title, details, the status items (icon class, text, details)
// and the expanded items. Pure Rhino. Opens __FILE__ first and waits __WAIT__ ms.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder("@@@")
var editor = null
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
} }), ModalityState.any())
java.lang.Thread.sleep(__WAIT__)
function plain(s) { return s == null ? "" : String(s).replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim() }
function items(list, label) {
    var it = list.iterator()
    while (it.hasNext()) {
        var s = it.next()
        var icon = s.getIcon(); var iconText = icon == null ? "-" : String(icon.toString()).replace(/^.*[\/\\]/, "")
        out.append("  " + label + ": text=«" + plain(s.getText()) + "» details=«" + plain(s.getDetailsText()) + "» icon=" + iconText + "\n")
    }
}
app.runReadAction(new java.lang.Runnable({ run: function () {
    var model = editor.getMarkupModel()
    var renderer = model.getErrorStripeRenderer()
    if (renderer == null) { out.append("no error stripe renderer\n"); return }
    out.append("renderer " + renderer.getClass().getName() + "\n")
    var status = renderer.getStatus()
    out.append("title=«" + plain(status.getTitle()) + "» details=«" + plain(status.getDetails()) + "» showNavigation=" + status.getShowNavigation() + " icon=" + status.getIcon() + "\n")
    items(status.getExpandedStatus(), "expanded")
    try { var passes = status.getPasses(); var pi = passes.iterator(); while (pi.hasNext()) { var p = pi.next(); out.append("  pass: " + plain(p.getPresentableName()) + " " + p.getProgress() + "\n") } } catch (e) {}
    try {
        var ctrl = status.getController()
        var actions = ctrl.getActions(); var ai = actions.iterator()
        while (ai.hasNext()) { var a = ai.next(); out.append("  action: " + plain(a.getTemplatePresentation().getText()) + " [" + String(a.getClass().getName()).replace(/^.*\./, "") + "]\n") }
        var levels = ctrl.getAvailableLevels(); var li = levels.iterator()
        while (li.hasNext()) { var lv = li.next(); out.append("  level: " + plain(lv.getName()) + "\n") }
    } catch (e) { out.append("controller: " + e + "\n") }
} }))
String(out)
