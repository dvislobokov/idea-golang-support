// Adds a function breakpoint named __NAME__ (through the companion of the type, as the action does) and lists the function breakpoints.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var type = cls("io.github.golangsupport.debugger.GoFunctionBreakpointType")
        var name = "__NAME__"
        if (name != "") type.getField("Companion").get(null).add(project, name)
        var all = XDebuggerManager.getInstance(project).getBreakpointManager().getAllBreakpoints()
        var out = []
        for (var i = 0; i < all.length; i++) {
            var b = all[i]
            if (String(b.getType().getId()) == "go-function") out.push(b.getType().getDisplayText(b) + " enabled=" + b.isEnabled())
        }
        done.complete("function breakpoints: " + out.join(" | "))
    } catch (e) { done.complete("failed: " + e) }
} }))
done.get(30, TimeUnit.SECONDS)
