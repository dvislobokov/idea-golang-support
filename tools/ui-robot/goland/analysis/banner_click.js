// Clicks the link __LINK__ of the editor banner of the selected editor (EditorNotificationPanel), then prints the caret line and the
// text of that line of the selected editor. Plain Rhino: GoLand and the plugin sandbox.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var out = "@@@"
var app = ApplicationManager.getApplication()
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var project = ps[ps.length - 1]
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var root = editor.getComponent()
    for (var k = 0; k < 8 && root.getParent() != null; k++) root = root.getParent()
    var found = null
    function walk(c) {
        if (found != null) return
        if (c instanceof com.intellij.ui.HyperlinkLabel && ("" + c.getText()).indexOf("__LINK__") >= 0) { found = c; return }
        if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) walk(ch[i]) }
    }
    walk(root)
    if (found == null) { out += "no link __LINK__"; return }
    found.doClick()
} }), ModalityState.any())
java.lang.Thread.sleep(1500)
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    if (out != "@@@") return
    var ps = ProjectManager.getInstance().getOpenProjects()
    var editor = FileEditorManager.getInstance(ps[ps.length - 1]).getSelectedTextEditor()
    var line = editor.getCaretModel().getLogicalPosition().line
    var d = editor.getDocument()
    out += "caret line " + (line + 1) + ": " + d.getText(new com.intellij.openapi.util.TextRange(d.getLineStartOffset(line), d.getLineEndOffset(line)))
} }), ModalityState.any())
out
