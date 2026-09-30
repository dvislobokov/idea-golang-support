// Clears "Don't ask again" of the plugin advisor and asks it to suggest again for the open project; prints whether it had been dismissed.
importClass(com.intellij.ide.util.PropertiesComponent)
importClass(com.intellij.openapi.project.ProjectManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var before = PropertiesComponent.getInstance().getBoolean("io.github.golangsupport.pluginAdvisor.dismissed", false)
PropertiesComponent.getInstance().setValue("io.github.golangsupport.pluginAdvisor.dismissed", false)
kotlinObject("io.github.golangsupport.sdk.GoPluginAdvisor").suggest(project)
"dismissed was " + before + ", suggested"
