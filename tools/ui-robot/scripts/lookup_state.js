// The completion list of the selected editor: shown or not, and the selected item.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects(); var project = projects[projects.length - 1]
var out = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var lookup = LookupManager.getActiveLookup(editor)
    out.complete(lookup == null ? "no lookup" : "lookup " + (lookup.isShown() ? "shown" : "hidden") + ", selected " + (lookup.getCurrentItem() == null ? "-" : lookup.getCurrentItem().getLookupString()))
} }))
out.get(20, TimeUnit.SECONDS)
