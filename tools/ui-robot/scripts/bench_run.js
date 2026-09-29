// Runs the benchmarks of __DIR__ through the launcher of the plugin; after the pause __WAIT__ ms prints the rows of the Benchmarks tab of the Go Tests window.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.wm.ToolWindowManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var started = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        kotlinObject("io.github.golangsupport.run.GoRunLauncher").runTests(project, "__DIR__", "bench store", null, true, false, false, false, false)
        started.complete("started")
    } catch (e) { started.complete("failed: " + e) }
} }))
var out = started.get(30, TimeUnit.SECONDS) + "\n"
java.lang.Thread.sleep(__WAIT__)
var read = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var window = ToolWindowManager.getInstance(project).getToolWindow("Go Tests")
        var contents = window.getContentManager().getContents()
        var text = "tabs:"
        for (var i = 0; i < contents.length; i++) text += " [" + contents[i].getTabName() + (window.getContentManager().getSelectedContent() == contents[i] ? "*" : "") + "]"
        var results = project.getService(cls("io.github.golangsupport.testing.GoBenchmarkResults"))
        var runs = results.latest()
        for (var r = 0; r < runs.size(); r++) {
            var run = runs.get(r)
            var before = results.previous(run.getPackagePath())
            text += "\n" + run.getPackagePath() + (before == null ? " (no run before)" : " (compared with the run before)")
            var list = run.getResults()
            for (var i = 0; i < list.size(); i++) {
                var b = list.get(i)
                text += "\n  " + b.getName() + "-" + b.getProcs() + " iterations=" + b.getIterations() + " " + b.getMetrics()
            }
        }
        read.complete(text)
    } catch (e) { read.complete("failed: " + e) }
} }), ModalityState.any())
out + read.get(30, TimeUnit.SECONDS)
