// docs/compare part 2 in GoLand: "restore the deleted identifier" for the sites __FROM__.. __TO__ (0-based, exclusive) of __SITES__
// (tools/compare/sites output, JSON) in the project at __DIR__. For each site and prefix 0/1/3: delete the identifier's tail (and the
// import line for unimported-package sites), invoke basic completion explicitly (autocomplete of a single item off for the run), wait
// until the lookup stops calculating, record the rank of the identifier among the lookup strings, the size and the time; restore the
// document. Results are appended as JSON lines to __OUT__. The document is restored with a minimal replace and saved unchanged.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.CodeInsightSettings)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var sites = JSON.parse(String(new java.lang.String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("__SITES__")), "UTF-8")))
var out = new java.io.OutputStreamWriter(new java.io.FileOutputStream("__OUT__", true), "UTF-8")
var settings = CodeInsightSettings.getInstance()
var autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
var done = 0
function utf8Chars(text, byteOffset) {
    var bytes = new java.lang.String(text).getBytes("UTF-8")
    return String(new java.lang.String(bytes, 0, byteOffset, "UTF-8")).length
}
function restore(editor, original) {
    var doc = editor.getDocument()
    var cur = String(doc.getText()), orig = String(original)
    if (cur == orig) return
    var a = 0
    while (a < cur.length && a < orig.length && cur.charAt(a) == orig.charAt(a)) a++
    var b = 0
    while (b < cur.length - a && b < orig.length - a && cur.charAt(cur.length - 1 - b) == orig.charAt(orig.length - 1 - b)) b++
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { doc.replaceString(a, cur.length - b, orig.substring(a, orig.length - b)) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}
try {
    for (var si = __FROM__; si < __TO__ && si < sites.length; si++) {
        var s = sites[si]
        var editor = null, original = null, at = -1, ir = null
        app.invokeAndWait(new java.lang.Runnable({ run: function () {
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__DIR__/" + s.file)
            editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
            original = String(editor.getDocument().getText())
            at = utf8Chars(original, s.offset)
            if (s.kind == "unimported-package") ir = [utf8Chars(original, s.importStart), utf8Chars(original, s.importEnd)]
        } }), com.intellij.openapi.application.ModalityState.any())
        if (original.substr(at, s.ident.length) != s.ident) {
            out.write(JSON.stringify({ site: s.id, ident: s.ident, kind: s.kind, sub: s.sub, prefix: -1, rank: -1, size: 0, millis: 0, top: [], note: "site text mismatch" }) + "\n")
            continue
        }
        var prefixes = [0, 1, 3].filter(function (p) { return p < s.ident.length })
        for (var pi = 0; pi < prefixes.length; pi++) {
            var prefix = prefixes[pi]
            app.invokeAndWait(new java.lang.Runnable({ run: function () {
                var doc = editor.getDocument()
                var caret = at + prefix
                WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
                    doc.deleteString(at + prefix, at + s.ident.length)
                    if (ir != null) { doc.deleteString(ir[0], ir[1]); caret -= ir[1] - ir[0] }
                } }))
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                editor.getCaretModel().moveToOffset(caret)
                editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
            } }), com.intellij.openapi.application.ModalityState.any())
            var t0 = java.lang.System.nanoTime()
            app.invokeAndWait(new java.lang.Runnable({ run: function () {
                new CodeCompletionHandlerBase(CompletionType.BASIC, true, false, true).invokeCompletion(project, editor, 1)
            } }), com.intellij.openapi.application.ModalityState.any())
            var strings = null, ms = -1, note = null
            for (var tick = 0; tick < 300; tick++) {
                java.lang.Thread.sleep(20)
                var state = null
                app.invokeAndWait(new java.lang.Runnable({ run: function () {
                    var lookup = LookupManager.getInstance(project).getActiveLookup()
                    if (lookup == null) { state = "none"; return }
                    if (lookup.isCalculating()) { state = "calc"; return }
                    state = "ready"
                    var items = lookup.getItems()
                    strings = []
                    for (var i = 0; i < items.size(); i++) strings.push(String(items.get(i).getLookupString()))
                } }), com.intellij.openapi.application.ModalityState.any())
                if (state == "ready") { ms = Math.round((java.lang.System.nanoTime() - t0) / 1e6); break }
                // no lookup after a while: nothing to show (GoLand shows "No suggestions" as a hint)
                if (state == "none" && tick > 150) { ms = Math.round((java.lang.System.nanoTime() - t0) / 1e6); note = "no lookup"; strings = []; break }
            }
            if (strings == null) { strings = []; note = "timeout" }
            var rank = strings.indexOf(s.ident)
            // a package offered for import: by its name, its import path, or (GoLand) a qualified member `pkg.Name` that brings the import
            if (rank < 0 && s.importPath) for (var k = 0; k < strings.length; k++) {
                if (strings[k] == s.importPath || strings[k].indexOf(s.ident + ".") == 0) { rank = k; note = "matched as " + strings[k]; break }
            }
            out.write(JSON.stringify({ site: s.id, file: s.file, line: s.line, ident: s.ident, kind: s.kind, sub: s.sub, prefix: prefix,
                rank: rank < 0 ? -1 : rank + 1, size: strings.length, millis: ms, top: strings.slice(0, 5), note: note,
                near: rank >= 0 ? [] : strings.filter(function (x) { return x.toLowerCase().indexOf(s.ident.toLowerCase()) >= 0 }).slice(0, 5) }) + "\n")
            out.flush()
            app.invokeAndWait(new java.lang.Runnable({ run: function () {
                LookupManager.getInstance(project).hideActiveLookup()
                restore(editor, original)
            } }), com.intellij.openapi.application.ModalityState.any())
            done++
        }
        app.invokeAndWait(new java.lang.Runnable({ run: function () {
            restore(editor, original)
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(editor.getDocument())
        } }), com.intellij.openapi.application.ModalityState.any())
    }
} finally {
    settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
    out.close()
}
"@@@" + "attempts: " + done
