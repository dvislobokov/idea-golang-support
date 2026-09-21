// Closes every project but the last opened one: two frames confuse everything that acts on "the" window.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
const projects = ProjectManager.getInstance().getOpenProjects()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    for (let i = 0; i < projects.length - 1; i++) ProjectManager.getInstance().closeAndDispose(projects[i])
} }))
"closing " + (projects.length - 1)
