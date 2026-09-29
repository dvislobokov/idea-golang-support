// The run configuration the producers make for the caret placed inside the first occurrence of __TEXT__ in the selected editor, and it is started with Run.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.execution.actions.ConfigurationContext)
importClass(com.intellij.execution.ProgramRunnerUtil)
importClass(com.intellij.execution.executors.DefaultRunExecutor)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const result = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        const document = editor.getDocument()
        editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1)
        const context = ConfigurationContext.getFromContext(DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown")
        const settings = context.getConfiguration()
        if (settings == null) { result.complete("no configuration from context"); return }
        const options = settings.getConfiguration().getOptions()
        let out = "configuration: " + settings.getName() + " pattern=" + options.getTestPattern() + " target=" + options.getTarget() + "\n"
        if ("__RUN__" == "yes") { ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance()); out += "started\n" }
        result.complete(out)
    } catch (e) { result.complete("failed: " + e + (e.stack ? "\n" + e.stack : "")) }
} }))
result.get(30, TimeUnit.SECONDS)
