// The editor notification banners (EditorNotificationPanel and other panels above the editor) of the selected editor: their texts and
// the texts of their links. Works on GoLand and on the plugin sandbox (plain Rhino, no prelude).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var out = "@@@"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var project = ps[ps.length - 1]
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    if (editor == null) { out += "no editor"; return }
    // up to the composite of the file editor, then every notification-like panel below it
    var root = editor.getComponent()
    for (var k = 0; k < 8 && root.getParent() != null; k++) root = root.getParent()
    var lines = []
    function texts(c, acc) {
        try { if (c.getText && ("" + c.getText()).trim().length > 0) acc.push(("" + c.getText()).replace(/<[^>]+>/g, "").trim().substring(0, 200)) } catch (e) {}
        if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) texts(ch[i], acc) }
    }
    function walk(c, d) {
        if (d > 40) return
        var n = "" + c.getClass().getName()
        if (c.isShowing() && (n.indexOf("NotificationPanel") >= 0 || n.indexOf("EditorNotification") >= 0 || n.indexOf("Banner") >= 0)) {
            var acc = []; texts(c, acc); lines.push(n.replace(/.*\./, "") + ": " + acc.join(" | ")); return
        }
        if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) walk(ch[i], d + 1) }
    }
    walk(root, 0)
    out += lines.length == 0 ? "no banners" : lines.join("\n")
} }))
out
