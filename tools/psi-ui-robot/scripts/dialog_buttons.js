// The open projects and the buttons of the dialogs that are open; clicks the button whose text contains __BUTTON__ (leave empty just to look).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(java.awt.Window)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var out = ""
    var projects = ProjectManager.getInstance().getOpenProjects()
    for (var p = 0; p < projects.length; p++) out += "project: " + projects[p].getBasePath() + "\n"
    var buttons = []
    function collect(c, depth) {
        if (c instanceof javax.swing.AbstractButton && !(c instanceof javax.swing.JCheckBox)) buttons.push(c)
        if (c instanceof java.awt.Container && depth < 30) { var kids = c.getComponents(); for (var i = 0; i < kids.length; i++) collect(kids[i], depth + 1) }
    }
    var windows = Window.getWindows()
    for (var w = 0; w < windows.length; w++) if (windows[w].isShowing() && windows[w] instanceof javax.swing.JDialog) { out += "dialog: " + windows[w].getTitle() + "\n"; collect(windows[w], 0) }
    var wanted = "__BUTTON__"
    for (var b = 0; b < buttons.length; b++) {
        var text = String(buttons[b].getText() || "")
        out += "  button: " + text + "\n"
        if (wanted.length > 0 && text.indexOf(wanted) >= 0) { buttons[b].doClick(); out += "  clicked: " + text + "\n"; break }
    }
    done.complete(out)
} }), ModalityState.any())
done.get(30, TimeUnit.SECONDS)
