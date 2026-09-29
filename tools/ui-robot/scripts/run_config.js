// Makes a temporary Go test configuration of __DIR__ with the flags __RACE__ / __NOCACHE__ (true/false), runs it, and after a pause prints the first console lines.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.execution.RunManager)
importClass(com.intellij.execution.ProgramRunnerUtil)
importClass(com.intellij.execution.executors.DefaultRunExecutor)
importClass(com.intellij.execution.ui.RunContentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
// GoConfigurationType.instance is a companion property: the factory is borrowed from an existing Go configuration instead
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var runManager = RunManager.getInstance(project)
        var all = runManager.getAllSettings()
        var factory = null
        for (var i = 0; i < all.size(); i++) if (String(all.get(i).getType().getId()) == "GoRunConfiguration") factory = all.get(i).getFactory()
        if (factory == null) { done.complete("no Go configuration to borrow the factory from"); return }
        var settings = runManager.createConfiguration("flags of store", factory)
        var options = settings.getConfiguration().getOptions()
        options.setCommand(java.lang.Enum.valueOf(cls("io.github.golangsupport.run.GoCommand"), "TEST"))
        options.setTarget("__DIR__")
        options.setRace(__RACE__)
        options.setNoTestCache(__NOCACHE__)
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        done.complete("started")
    } catch (e) { done.complete("failed: " + e) }
} }))
var first = done.get(30, TimeUnit.SECONDS)
java.lang.Thread.sleep(12000)
var out = first + "\n"
var descriptors = RunContentManager.getInstance(project).getAllDescriptors()
for (var d = 0; d < descriptors.size(); d++) {
    var descriptor = descriptors.get(d)
    if (String(descriptor.getDisplayName()) != "flags of store") continue
    var console = descriptor.getExecutionConsole()
    var view = console.getClass().getMethod("getConsole").invoke(console)
    var text = String(view.getText())
    out += text.split("\n")[0] + "\n"
}
out
