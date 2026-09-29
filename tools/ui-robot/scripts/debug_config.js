// Makes a temporary Go configuration of the kind __KIND__ (EXEC, CORE, REMOTE) with __BINARY__, __CORE__, __HOST__, __PORT__, __PID__ and starts it with Debug.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.execution.RunManager)
importClass(com.intellij.execution.ProgramRunnerUtil)
importClass(com.intellij.execution.executors.DefaultDebugExecutor)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var runManager = RunManager.getInstance(project)
        var all = runManager.getAllSettings()
        var factory = null
        for (var i = 0; i < all.size(); i++) if (String(all.get(i).getType().getId()) == "GoRunConfiguration") factory = all.get(i).getFactory()
        if (factory == null) { done.complete("no Go configuration to borrow the factory from"); return }
        var settings = runManager.createConfiguration("__KIND__ session", factory)
        var options = settings.getConfiguration().getOptions()
        options.setCommand(java.lang.Enum.valueOf(cls("io.github.golangsupport.run.GoCommand"), "__KIND__"))
        options.setBinary("__BINARY__")
        options.setCoreFile("__CORE__")
        options.setRemoteHost("__HOST__")
        options.setRemotePort(java.lang.Integer.parseInt("__PORT__"))
        options.setRemotePid(java.lang.Integer.parseInt("__PID__"))
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        done.complete("started __KIND__")
    } catch (e) { done.complete("failed: " + e) }
} }))
done.get(30, TimeUnit.SECONDS)
