// Runs the tests of __DIR__ with the profile __PROFILE__ (CPU, MEMORY...), waits for the run to end, then opens the profile in a tab
// of the editor the way the notification button does, and lists the editor tabs.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.execution.RunManager)
importClass(com.intellij.execution.ProgramRunnerUtil)
importClass(com.intellij.execution.executors.DefaultRunExecutor)
importClass(com.intellij.execution.ui.RunContentManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
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
        var settings = runManager.createConfiguration("profile of store", factory)
        var options = settings.getConfiguration().getOptions()
        options.setCommand(java.lang.Enum.valueOf(cls("io.github.golangsupport.run.GoCommand"), "TEST"))
        options.setTarget("__DIR__")
        options.setBenchmark(__BENCH__)
        options.setProfile(java.lang.Enum.valueOf(cls("io.github.golangsupport.monitor.GoProfile"), "__PROFILE__"))
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        done.complete("started")
    } catch (e) { done.complete("failed: " + e) }
} }))
var out = done.get(30, TimeUnit.SECONDS) + "\n"
// wait for the run to end
var terminated = false
for (var t = 0; t < 60 && !terminated; t++) {
    java.lang.Thread.sleep(1000)
    var descriptors = RunContentManager.getInstance(project).getAllDescriptors()
    for (var d = 0; d < descriptors.size(); d++) {
        var descriptor = descriptors.get(d)
        if (String(descriptor.getDisplayName()) == "profile of store" && descriptor.getProcessHandler() != null && descriptor.getProcessHandler().isProcessTerminated()) terminated = true
    }
}
out += "terminated=" + terminated + "\n"
// the newest profile directory of this run
var tmp = new java.io.File(java.lang.System.getProperty("java.io.tmpdir"))
var dirs = tmp.listFiles(new java.io.FilenameFilter({ accept: function (dir, name) { return String(name).indexOf("go-profile-") == 0 } }))
var newest = null
for (var i = 0; i < dirs.length; i++) if (newest == null || dirs[i].lastModified() > newest.lastModified()) newest = dirs[i]
var file = new java.io.File(newest, "cpu.pprof")
out += "profile: " + file + " " + file.length() + " bytes\n"
var servers = project.getService(cls("io.github.golangsupport.monitor.GoProfileServers"))
var profile = java.lang.Enum.valueOf(cls("io.github.golangsupport.monitor.GoProfile"), "__PROFILE__")
servers.open(profile, file, true, profile.getTitle())
java.lang.Thread.sleep(8000)
var opened = FileEditorManager.getInstance(project).getOpenFiles()
var names = []
for (var i = 0; i < opened.length; i++) names.push(String(opened[i].getName()))
out += "editor tabs: " + names.join(", ")
out
