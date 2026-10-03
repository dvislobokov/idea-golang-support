// Turns the inspection __SHORT__ on (__ON__ = yes) or off in the current profile of the last opened project, for inspections that are
// off by default (GoDocComment); the daemon restarts so the open editors are highlighted again.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.profile.codeInspection.InspectionProjectProfileManager)
importClass(com.intellij.codeInsight.daemon.DaemonCodeAnalyzer)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var profile = InspectionProjectProfileManager.getInstance(project).getCurrentProfile()
        if ("__ON__" == "yes") profile.enableTool("__SHORT__", project); else profile.disableTool("__SHORT__", project)
        DaemonCodeAnalyzer.getInstance(project).restart()
        done.complete("__SHORT__ turned " + ("__ON__" == "yes" ? "on" : "off") + " in " + profile.getName())
    } catch (e) { done.complete("failed: " + e) }
} }), ModalityState.any())
done.get(20, TimeUnit.SECONDS)
