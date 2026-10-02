// The text of the selected editor, tabs made visible.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ReadAction.compute(function () { return String(FileEditorManager.getInstance(project).getSelectedTextEditor().getDocument().getText()).split("\t").join("<TAB>") })
