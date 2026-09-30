// Sets the hit count __HITS__ on the function breakpoint named __NAME__ and removes the other function breakpoints.
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
        var manager = XDebuggerManager.getInstance(project).getBreakpointManager()
        var all = manager.getAllBreakpoints()
        var out = []
        for (var i = 0; i < all.length; i++) {
            var b = all[i]
            if (String(b.getType().getId()) != "go-function") continue
            if (String(b.getType().getDisplayText(b)) == "__NAME__") {
                b.getProperties().setHitCondition("__HITS__")
                b.fireBreakpointChanged()
                out.push(b.getType().getDisplayText(b) + " hits=" + b.getProperties().getHitCondition())
            } else {
                ApplicationManager.getApplication().runWriteAction(new java.lang.Runnable({ run: function () { manager.removeBreakpoint(b) } }))
            }
        }
        done.complete("kept: " + out.join(" | "))
    } catch (e) { done.complete("failed: " + e) }
} }))
done.get(30, TimeUnit.SECONDS)
