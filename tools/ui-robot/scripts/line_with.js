// The line of the selected editor that contains __TEXT__, and the same line of the file on disk.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const document = FileEditorManager.getInstance(project).getSelectedTextEditor().getDocument()
const file = FileDocumentManager.getInstance().getFile(document)
function lineOf(text) { const lines = String(text).split("\n"); for (let i = 0; i < lines.length; i++) if (lines[i].indexOf("__TEXT__") >= 0) return lines[i]; return "?" }
"editor: [" + lineOf(document.getText()) + "]\ndisk:   [" + lineOf(new java.lang.String(file.contentsToByteArray(), "UTF-8")) + "]\nunsaved: " + FileDocumentManager.getInstance().isDocumentUnsaved(document)
