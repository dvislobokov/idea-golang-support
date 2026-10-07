// The grey text of the inline completion shown in the selected editor (the session of the platform), or "no session".
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.inline.completion.session.InlineCompletionSession)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var session = InlineCompletionSession.Companion.getOrNull(editor)
        if (session == null) { out.complete("no session"); return }
        var ctx = session.getContext()
        out.complete("provider " + String(session.getProvider()) + ": [" + ctx.textToInsert() + "]")
    } catch (e) { out.complete("failed: " + e + (e.javaException ? " / " + e.javaException : "")) }
} }))
out.get(20, TimeUnit.SECONDS)
