// Coverage: __MODE__ = run (start `go test` of __DIR__ with coverage) or check (what the service holds and the highlighters of the selected editor).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var result = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        if ("__MODE__" == "run") {
            kotlinObject("io.github.golangsupport.run.GoRunLauncher").runTests(project, "__DIR__", "coverage of store", null, false, false, false, false, true)
            result.complete("started")
            return
        }
        var service = project.getService(cls("io.github.golangsupport.testing.GoCoverageService"))
        var data = service.getData()
        let out = "data: " + (data == null ? "none" : data.getBlocks().size() + " blocks, files " + data.getByFile().keySet() + ", percent " + data.percent(function (f) { return true })) + "\n"
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument())
        var highlighters = editor.getMarkupModel().getAllHighlighters()
        let bars = 0, lines = ""
        for (let i = 0; i < highlighters.length; i++) {
            var renderer = highlighters[i].getLineMarkerRenderer()
            if (renderer != null && String(renderer.getClass().getName()).indexOf("GoCoverage") >= 0) { bars++; if (bars <= 12) lines += (editor.getDocument().getLineNumber(highlighters[i].getStartOffset()) + 1) + " " }
        }
        out += file.getName() + ": " + bars + " coverage bars, lines " + lines + "\n"
        result.complete(out)
    } catch (e) { result.complete("failed: " + e + (e.stack ? "\n" + e.stack : "")) }
} }))
result.get(30, TimeUnit.SECONDS)
