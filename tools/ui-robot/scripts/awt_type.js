// Types __TEXT__ into the focused editor with java.awt.Robot, __DELAY__ ms between keys (real key events through the IDE queue). ASCII only.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.wm.IdeFocusManager)

importClass(java.awt.event.KeyEvent)
var projects = ProjectManager.getInstance().getOpenProjects(); var project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true)
} }))
var robot = new java.awt.Robot(); robot.setAutoDelay(20)
var text = "__TEXT__"
for (var i = 0; i < text.length; i++) {
    var ch = text.charAt(i)
    if (ch == "\n") { robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER) }
    else {
        var code = KeyEvent.getExtendedKeyCodeForChar(ch.charCodeAt(0))
        var shift = (ch != ch.toLowerCase()) || "(){}[]*&|<>_+:\"".indexOf(ch) >= 0
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(code); robot.keyRelease(code)
        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT)
    }
    java.lang.Thread.sleep(__DELAY__)
}
"typed " + text.length + " keys"
