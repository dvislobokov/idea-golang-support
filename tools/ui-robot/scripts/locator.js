// Where the test tree leads for the test __TEST__ (e.g. TestTotal/empty) of the package directory __DIR__: the file and the line.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.search.GlobalSearchScope)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var locator = kotlinObject("io.github.golangsupport.testing.GoTestLocator")
    var locations = locator.getLocation("gotest", "__DIR__|__TEST__", project, GlobalSearchScope.projectScope(project))
    if (locations.size() == 0) return "no location"
    var element = locations.get(0).getPsiElement()
    var doc = PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile())
    var line = doc.getLineNumber(element.getTextOffset())
    return element.getContainingFile().getName() + ":" + (line + 1) + "  " + String(doc.getText().substring(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim()
})
