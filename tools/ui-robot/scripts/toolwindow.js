// Shows the tool window with the id __ID__.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.wm.ToolWindowManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    const window = ToolWindowManager.getInstance(project).getToolWindow("__ID__")
    if (window != null) window.activate(null)
} }))
"ok"
