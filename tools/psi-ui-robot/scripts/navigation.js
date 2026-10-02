// What the IDE finds for the declaration named __NAME__ in the file of the selected editor: its usages (the custom usage searchers, as
// Find Usages runs them), its implementations (DefinitionsScopedSearch, as Go to Implementation does), and where Go to Declaration
// leads from the first usage.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiTreeUtil)
importClass(com.intellij.find.findUsages.CustomUsageSearcher)
importClass(com.intellij.find.findUsages.FindUsagesOptions)
importClass(com.intellij.psi.search.searches.DefinitionsScopedSearch)
importClass(com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler)
importClass(com.intellij.util.Processor)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
const document = editor.getDocument()

const declaration = ReadAction.compute(function () {
    const psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    let found = null
    const all = PsiTreeUtil.collectElements(psi, function (e) { return e instanceof com.intellij.psi.PsiNameIdentifierOwner && String(e.getName()) == "__NAME__" })
    return all.length > 0 ? all[0] : null
})
let out = "declaration: " + declaration + "\n"

function describe(file, offset) { return ReadAction.compute(function () {
    const doc = PsiDocumentManager.getInstance(project).getDocument(file)
    const line = doc.getLineNumber(offset)
    return file.getName() + ":" + (line + 1) + "  " + String(doc.getText().substring(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim()
}) }

const usages = new java.util.ArrayList()
const findOptions = ReadAction.compute(function () { return new FindUsagesOptions(project) })
const searchers = CustomUsageSearcher.EP_NAME.getExtensionList()
for (let i = 0; i < searchers.size(); i++) {
    searchers.get(i).processElementUsages(declaration, new Processor({ process: function (usage) { usages.add(usage); return true } }), findOptions)
}
out += "usages: " + usages.size() + "\n"
// `var`: a `const` of Rhino belongs to the function, not to the iteration, and keeps its first value
for (var i = 0; i < usages.size(); i++) { var usageInfo = usages.get(i).getUsageInfo(); out += "  " + describe(usageInfo.getFile(), usageInfo.getNavigationOffset()) + "\n" }

const query = ReadAction.compute(function () { return DefinitionsScopedSearch.search(declaration) })
const implementations = query.findAll().toArray()
out += "implementations: " + implementations.length + "\n"
for (let i = 0; i < implementations.length; i++) out += "  " + ReadAction.compute(function () { return implementations[i] + " in " + implementations[i].getContainingFile().getName() }) + "\n"

if (usages.size() > 0) {
    const first = usages.get(0).getUsageInfo()
    const targets = ReadAction.compute(function () {
        const leaf = first.getFile().findElementAt(first.getNavigationOffset())
        const handlers = GotoDeclarationHandler.EP_NAME.getExtensionList()
        for (let i = 0; i < handlers.size(); i++) {
            const found = handlers.get(i).getGotoDeclarationTargets(leaf, first.getNavigationOffset(), editor)
            if (found != null && found.length > 0) return handlers.get(i).getClass().getSimpleName() + " -> " + found[0]
        }
        return "no handler has a target"
    })
    out += "go to declaration from the first usage: " + targets + "\n"
}
out
