// Reloads the selected editor's document from disk (drops the edits of a robot session).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects(); var project = projects[projects.length - 1]
var out = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    ApplicationManager.getApplication().runWriteAction(new java.lang.Runnable({ run: function () {
        var d = FileEditorManager.getInstance(project).getSelectedTextEditor().getDocument()
        FileDocumentManager.getInstance().reloadFromDisk(d)
        out.complete("reloaded, " + d.getLineCount() + " lines")
    } }))
} }))
out.get(20, TimeUnit.SECONDS)
