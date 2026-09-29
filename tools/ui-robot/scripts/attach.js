// Attaches the debugger to the process __PID__ (as the Debug button of the Go Monitor and Run | Attach to Process do).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.execution.ExecutionManager)
importClass(com.intellij.execution.executors.DefaultDebugExecutor)
importClass(com.intellij.execution.runners.ExecutionEnvironmentBuilder)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var profileClass = cls("io.github.golangsupport.debugger.GoAttachProfile")
        var profile = profileClass.getConstructor(java.lang.Integer.TYPE, java.lang.String).newInstance(java.lang.Integer.valueOf(__PID__), "attached wait")
        var environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), profile).build()
        ExecutionManager.getInstance(project).restartRunProfile(environment)
        done.complete("attaching to __PID__")
    } catch (e) { done.complete("failed: " + e) }
} }))
done.get(30, TimeUnit.SECONDS)
