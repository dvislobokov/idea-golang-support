// Which GotoDeclarationHandler answers for the first occurrence of __TEXT__ in __FILE__ (a path), and with what.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiManager)
importClass(com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
ReadAction.compute(function () {
    const file = PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().findFileByPath("__FILE__"))
    const offset = String(file.getText()).indexOf("__TEXT__")
    const leaf = file.findElementAt(offset)
    var out = "leaf: " + leaf + " '" + leaf.getText() + "' in " + file.getName() + " at " + offset + "\n"
    const handlers = GotoDeclarationHandler.EP_NAME.getExtensionList()
    for (var i = 0; i < handlers.size(); i++) {
        var found = null, error = ""
        try { found = handlers.get(i).getGotoDeclarationTargets(leaf, offset, editor) } catch (e) { error = " threw " + e }
        out += "  " + handlers.get(i).getClass().getName() + ": " + (found == null ? "null" : found.length + " -> " + (found.length > 0 ? found[0] : "")) + error + "\n"
    }
    return out
})
