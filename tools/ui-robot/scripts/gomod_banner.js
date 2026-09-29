// Appends a require line to go.mod (the selected editor), saves it, and reports whether the banner of the plugin is pending for the file.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const result = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        const document = editor.getDocument()
        const file = FileDocumentManager.getInstance().getFile(document)
        const changes = project.getService(cls("io.github.golangsupport.mod.GoModChanges"))
        let out = "before: pending=" + changes.isPending(file) + "\n"
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
            document.insertString(document.getTextLength(), "\nrequire (\n\tgolang.org/x/text v0.20.0\n)\n")
        } }))
        FileDocumentManager.getInstance().saveDocument(document)
        out += "after save: pending=" + changes.isPending(file) + "\n"
        result.complete(out)
    } catch (e) { result.complete("failed: " + e) }
} }))
result.get(30, TimeUnit.SECONDS)
