// Performs the action __ID__ on EDT with the data context of the frame of the project (the focus owner of the robot may be a component that is not showing).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ActionPlaces)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.openapi.wm.WindowManager)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var action = ActionManager.getInstance().getAction("__ID__")
    // the selected editor, when there is one: actions of the editor (Generate, intentions) find their caret and file through it
    var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor()
    var component = editor != null ? editor.getContentComponent() : WindowManager.getInstance().getFrame(project).getComponent()
    ActionUtil.invokeAction(action, DataManager.getInstance().getDataContext(component), ActionPlaces.MAIN_MENU, null, null)
} }))
"invoked __ID__"
