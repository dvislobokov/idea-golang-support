// Makes a temporary Go configuration (__COMMAND__: RUN or TEST) of the package __TARGET__ debugged on the SSH host __HOST__ with the
// "Files to copy" __FILES__ (lines separated by ";", may be empty) and starts it with Debug.
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
        var type = cls("io.github.golangsupport.run.GoConfigurationType")
        var factory = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(type).getFactory()
        var settings = runManager.createConfiguration("SSH __COMMAND__", factory)
        var options = settings.getConfiguration().getOptions()
        options.setCommand(java.lang.Enum.valueOf(cls("io.github.golangsupport.run.GoCommand"), "__COMMAND__"))
        options.setTarget("__TARGET__")
        options.setSshHost("__HOST__")
        options.setSshFiles("__FILES__".split(";").join("\n"))
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        done.complete("started SSH __COMMAND__ on __HOST__")
    } catch (e) { done.complete("failed: " + e) }
} }))
done.get(30, TimeUnit.SECONDS)
