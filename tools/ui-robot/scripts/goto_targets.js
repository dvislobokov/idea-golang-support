// Go to Declaration targets for the identifiers __NAMES__ (comma separated) in the selected editor, and the synthetic Go libraries of the project.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.navigation.actions.GotoDeclarationAction)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = ""
var names = "__NAMES__".split(",")
ReadAction.run(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var text = String(editor.getDocument().getText())
    for (var i = 0; i < names.length; i++) {
        var at = text.indexOf(names[i])
        if (at < 0) { out += names[i] + ": not in file\n"; continue }
        var targets = GotoDeclarationAction.findAllTargetElements(project, editor, at + 1)
        out += names[i] + ": " + targets.length + " target(s)"
        for (var j = 0; j < targets.length; j++) {
            var f = targets[j].getContainingFile()
            out += " -> " + (f == null ? targets[j] : f.getVirtualFile().getPath())
        }
        out += "\n"
    }
    var provider = cls("io.github.golangsupport.project.impl.GoRootsProvider").getDeclaredConstructor().newInstance()
    var libs = provider.getAdditionalProjectLibraries(project).toArray()
    out += "libraries: " + libs.length + "\n"
    for (var k = 0; k < libs.length; k++) out += "  " + libs[k].getComparisonId() + ": " + libs[k].getSourceRoots().size() + " root(s) " + libs[k].getSourceRoots().iterator().next().getPath() + "\n"
    out += "setting: " + ApplicationManager.getApplication().getService(cls("io.github.golangsupport.settings.GoSettings")).getLibraryRoots() + "\n"
})
out
