// What the semantic layer sees of the package in __DIR__ and the checker's diagnostics of __FILE__ (last project): the project model's
// files of the package, the scope an importer gets (names), the diagnostics of the file. For "stale after a change" questions.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.psi.PsiManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var out = []
    var dir = LocalFileSystem.getInstance().refreshAndFindFileByPath("__DIR__")
    var resolverCls = cls("io.github.golangsupport.project.api.GoPackageResolver")
    var resolver = resolverCls.getMethod("getInstance", com.intellij.openapi.project.Project).invoke(null, project)
    var pkg = resolver.packageOf(dir)
    out.push("model files: " + (pkg == null ? "null" : pkg.getGoFiles().toString()))
    var model = cls("io.github.golangsupport.semantic.scope.GoPackageModel").getMethod("getInstance", com.intellij.openapi.project.Project).invoke(null, project)
    if (pkg != null) {
        var scope = model.scopeOf(pkg)
        var names = []
        var it = scope.allDeclarations().iterator()
        while (it.hasNext()) names.push(it.next().getName())
        out.push("scope names: " + names.join(","))
    }
    var vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var file = PsiManager.getInstance(project).findFile(vf)
    var service = cls("io.github.golangsupport.semantic.api.GoSemanticService").getMethod("getInstance", com.intellij.openapi.project.Project).invoke(null, project)
    var trackers = cls("io.github.golangsupport.semantic.cache.GoTrackers").getMethod("getInstance", com.intellij.openapi.project.Project).invoke(null, project)
    var deps = trackers.packageDependencies(file)
    var counts = []
    for (var j = 0; j < deps.length; j++) counts.push(deps[j].getClass().getSimpleName() + ":" + (deps[j].getModificationCount ? deps[j].getModificationCount() : "?"))
    out.push("deps of the file: " + counts.join(", "))
    var ds = service.check(file)
    for (var i = 0; i < ds.size(); i++) out.push("diagnostic: " + ds.get(i).getMessage())
    return out.join("\n")
})
