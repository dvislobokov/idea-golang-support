// Opens __FILE__ (absolute, forward slashes) with the caret on line __LINE__ (1-based); __CLOSE_OTHERS__ = yes closes other editors.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var out = "@@@"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var project = ps[ps.length - 1]
    var fem = FileEditorManager.getInstance(project)
    if ("__CLOSE_OTHERS__" == "yes") { var open = fem.getOpenFiles(); for (var i = 0; i < open.length; i++) fem.closeFile(open[i]) }
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    if (file == null) { out += "no file __FILE__"; return }
    var ed = fem.openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, __LINE__ - 1, 0), true)
    ed.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    out += "opened " + file.getName() + " lines " + ed.getDocument().getLineCount()
} }))
out
