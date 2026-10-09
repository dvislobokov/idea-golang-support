// Types __TEXT__ into the selected editor as key events dispatched through the IDE event queue (no OS focus involved), __DELAY__ ms apart.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.ide.IdeEventQueue)
importClass(java.awt.event.KeyEvent)
var projects = ProjectManager.getInstance().getOpenProjects(); var project = projects[projects.length - 1]
var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
var component = editor.getContentComponent()
var text = "__TEXT__"
function send(ch) {
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        component.requestFocusInWindow()
        var now = java.lang.System.currentTimeMillis()
        var code = ch == "\n" ? KeyEvent.VK_ENTER : KeyEvent.getExtendedKeyCodeForChar(ch.charCodeAt(0))
        var q = IdeEventQueue.getInstance()
        q.dispatchEvent(new KeyEvent(component, KeyEvent.KEY_PRESSED, now, 0, code, ch == "\n" ? "\n" : ch))
        q.dispatchEvent(new KeyEvent(component, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch == "\n" ? "\n" : ch))
        q.dispatchEvent(new KeyEvent(component, KeyEvent.KEY_RELEASED, now, 0, code, ch == "\n" ? "\n" : ch))
    } }))
}
for (var i = 0; i < text.length; i++) { send(text.charAt(i)); java.lang.Thread.sleep(__DELAY__) }
"typed " + text.length + " keys"
