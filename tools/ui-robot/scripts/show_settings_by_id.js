// Opens the Settings dialog on the page with the configurable id __ID__ (display names repeat: "Go" is a colour scheme page too).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.options.ShowSettingsUtil)
importClass(com.intellij.openapi.options.ex.ConfigurableVisitor)
importClass(com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
    run: function () {
        const group = ConfigurableExtensionPointUtil.getConfigurableGroup(project, true)
        const page = ConfigurableVisitor.findById("__ID__", java.util.List.of(group))
        ShowSettingsUtil.getInstance().editConfigurable(project, page)
    }
}))
"ok"
