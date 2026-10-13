// Typing / keyboard batch for GoLand (docs/compare/prometheus-typing-dumps): runs every scenario of tools/compare/typing/scenarios.json
// (made by tools/compare/typing/to_json.py from scenarios.txt) in a scratch package __DIR__/typingcmp/<id>/ of the open project and writes the
// results in the shape of build/compare/out/typing.json of the plugin stand (GoTypingHarnessTest) to __OUT__. Plain Rhino, platform APIs only.
// Placeholders: __SCENARIOS__ (scenarios.json), __DIR__ (project root, forward slashes), __OUT__ (result JSON), __ONLY__ (ids, comma-separated, or
// empty), __GOROOT__ (GOROOT with forward slashes, or empty: paths after "/src/" outside the project count as GOROOT), __SYNC__ (ms after opening
// a file: highlighting, inlays), __PAUSE__ (ms after a key), __SLOW__ (ms after Reformat / Optimize Imports / save).
// Keys go as the keyboard sends them: characters through TypedAction (the typed handlers), {ENTER} and the rest through the editor actions.
// Usage (Git Bash, GoLand robot on 8595, see gj.sh):
//   gj $A/typing_batch.js "s|__SCENARIOS__|$R/tools/compare/typing/scenarios.json|" "s|__DIR__|$P|" "s|__OUT__|$R/build/compare/out/typing-goland.json|" \
//      "s|__ONLY__||" "s|__GOROOT__|C:/Program Files/Go|" "s|__SYNC__|1500|" "s|__PAUSE__|150|" "s|__SLOW__|3000|"
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.PsiManager)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.AnActionEvent)
importClass(com.intellij.openapi.actionSystem.ActionUiKind)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.vfs.VfsUtil)
importClass(com.intellij.openapi.util.TextRange)
importClass(com.intellij.openapi.project.DumbService)

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var DIR = "__DIR__", OUT = "__OUT__", ONLY = "__ONLY__", GOROOT = "__GOROOT__"
var SYNC = parseInt("__SYNC__") || 1500, PAUSE = parseInt("__PAUSE__") || 150, SLOW = parseInt("__SLOW__") || 3000
var scenarios = JSON.parse(String(new java.lang.String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("__SCENARIOS__")), "UTF-8")))
var only = ONLY.length > 0 ? ("," + ONLY + ",") : null

function edt(f) {
    var box = { value: null, error: null }
    app.invokeAndWait(new java.lang.Runnable({ run: function () { try { box.value = f() } catch (e) { box.error = e } } }), ModalityState.any())
    if (box.error != null) throw box.error
    return box.value
}
function read(f) {
    return ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () { return f() } }))
}
function write(f) {
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { f() } }))
}
function sleep(ms) { java.lang.Thread.sleep(ms) }
function smart() { try { DumbService.getInstance(project).waitForSmartMode() } catch (e) {} }

// ------------------------------------------------------------------ markers

function markers(code) {
    var text = code, caret = -1, ss = -1, se = -1
    while (true) {
        var c = text.indexOf("<caret>"), s = text.indexOf("<selection>"), e = text.indexOf("</selection>")
        var first = -1
        ;[c, s, e].forEach(function (x) { if (x >= 0 && (first < 0 || x < first)) first = x })
        if (first < 0) break
        if (first == c) { caret = c; text = text.substring(0, c) + text.substring(c + 7) }
        else if (first == s) { ss = s; text = text.substring(0, s) + text.substring(s + 11) }
        else { se = e; text = text.substring(0, e) + text.substring(e + 12) }
    }
    if (caret < 0) caret = se >= 0 ? se : 0
    return { text: text, caret: caret, selStart: ss, selEnd: se }
}
function withMarkers(editor) {
    var text = String(editor.getDocument().getText())
    var ins = []
    var sel = editor.getSelectionModel()
    if (sel.hasSelection()) { ins.push([sel.getSelectionStart(), 0, "<selection>"]); ins.push([sel.getSelectionEnd(), 2, "</selection>"]) }
    ins.push([editor.getCaretModel().getOffset(), 1, "<caret>"])
    ins.sort(function (a, b) { return a[0] != b[0] ? b[0] - a[0] : b[1] - a[1] })
    for (var i = 0; i < ins.length; i++) text = text.substring(0, ins[i][0]) + ins[i][2] + text.substring(ins[i][0])
    return text
}
function caretLine(editor) {
    var m = withMarkers(editor), at = m.indexOf("<caret>")
    var start = m.lastIndexOf("\n", at - 1) + 1, end = m.indexOf("\n", at)
    return m.substring(start, end < 0 ? m.length : end)
}

// ------------------------------------------------------------------ targets

function path(vf, dir) {
    if (vf == null) return "<no file>"
    var p = String(vf.getPath())
    var marker = "/" + dir + "/"
    var i = p.indexOf(marker)
    if (i >= 0) return p.substring(i + marker.length)
    if (GOROOT.length > 0 && p.toLowerCase().indexOf(GOROOT.toLowerCase() + "/") == 0) return "$GOROOT/" + p.substring(GOROOT.length + 1)
    var m = p.indexOf("/pkg/mod/")
    if (m >= 0) return "$GOMODCACHE/" + p.substring(m + 9)
    if (GOROOT.length == 0 && p.indexOf(DIR) != 0) { var s = p.indexOf("/src/"); if (s >= 0) return "$GOROOT" + p.substring(s) }
    return p
}
function lineAt(doc, offset, p) {
    var line = doc.getLineNumber(offset)
    return p + ":" + (line + 1) + ": " + String(doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)))).trim()
}
function describe(e, dir) {
    if (e instanceof com.intellij.psi.PsiDirectory) return "dir:" + path(e.getVirtualFile(), dir)
    var anchor = e
    try { if (e.getNameIdentifier && e.getNameIdentifier() != null) anchor = e.getNameIdentifier() } catch (x) {}
    var file = anchor.getContainingFile()
    if (file == null) return String(e)
    var doc = PsiDocumentManager.getInstance(project).getDocument(file)
    if (doc == null) return String(e)
    var r = anchor.getTextRange()
    return lineAt(doc, r != null ? r.getStartOffset() : anchor.getTextOffset(), path(file.getVirtualFile(), dir))
}
function list(javaList) { var out = []; var it = javaList.iterator(); while (it.hasNext()) out.push(it.next()); return out }

function plainText(html) {
    if (html == null) return ""
    return String(html).replace(/<(br|p|div|tr|li|h\d|pre)[^>]*>/gi, " ").replace(/<[^>]+>/g, "")
        .replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"").replace(/&#39;/g, "'").replace(/&amp;/g, "&")
        .replace(/\s+/g, " ").trim()
}
function clean(text) { return String(text == null ? "" : text).replace(/_/g, "").replace(/&/g, "").replace(/^\s*\w\.\s+/, "").trim() }

// ------------------------------------------------------------------ actions and popups as lists

function dataContext(editor) { return DataManager.getInstance().getDataContext(editor.getContentComponent()) }
function eventFor(action, ctx) {
    return AnActionEvent.createEvent(action, ctx, action.getTemplatePresentation().clone(), "EditorPopup", ActionUiKind.POPUP, null)
}
function surroundItems(editor, file) {
    var actions = com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler.buildSurroundActions(project, editor, file)
    var ctx = dataContext(editor), out = []
    for (var i = 0; i < actions.size(); i++) {
        var a = actions.get(i)
        if (a instanceof com.intellij.openapi.actionSystem.Separator) continue
        var ev = eventFor(a, ctx)
        try { ActionUtil.performDumbAwareUpdate(a, ev, false) } catch (x) {}
        out.push({ title: clean(ev.getPresentation().getText() || a.getTemplatePresentation().getText()), action: a })
    }
    return out
}
function generateItems(editor) {
    var group = ActionManager.getInstance().getAction("GenerateGroup")
    if (group == null) return ["<no GenerateGroup>"]
    var ctx = dataContext(editor), out = []
    function walk(g, depth) {
        var children = g.getChildren(eventFor(g, ctx))
        for (var i = 0; i < children.length; i++) {
            var c = children[i]
            if (c instanceof com.intellij.openapi.actionSystem.Separator) continue
            var ev = eventFor(c, ctx)
            try { ActionUtil.performDumbAwareUpdate(c, ev, false) } catch (x) { continue }
            var p = ev.getPresentation()
            if (!p.isVisible() || !p.isEnabled()) continue // the Generate popup hides disabled items
            var isGroup = c instanceof com.intellij.openapi.actionSystem.ActionGroup
            if (isGroup && !p.isPopupGroup()) walk(c, depth)
            else {
                var pad = ""; for (var k = 0; k < depth; k++) pad += "  "
                out.push(pad + clean(p.getText()))
                if (isGroup) walk(c, depth + 1)
            }
        }
    }
    walk(group, 0)
    return out
}

// ------------------------------------------------------------------ queries

function declaration(editor, dir) {
    var targets = edt(function () { return com.intellij.codeInsight.navigation.actions.GotoDeclarationAction.findAllTargetElements(project, editor, editor.getCaretModel().getOffset()) })
    return edt(function () { var out = []; for (var i = 0; i < targets.length; i++) out.push(describe(targets[i], dir)); return out })
}
function implementations(editor, file, dir) {
    var data = edt(function () { return new com.intellij.codeInsight.navigation.GotoImplementationHandler().getSourceAndTargetElements(editor, file) })
    if (data == null) return ["<no source element>"]
    var found = {}, out = []
    function add(e) { var d = describe(e, dir); if (!found[d]) { found[d] = true; out.push(d) } }
    edt(function () { for (var i = 0; i < data.targets.length; i++) add(data.targets[i]) })
    if (data.listUpdaterTask != null || out.length == 0) {
        // the popup would fill the rest in the background: the same search, synchronously
        var all = read(function () {
            return com.intellij.psi.search.searches.DefinitionsScopedSearch.search(data.source, com.intellij.psi.search.GlobalSearchScope.allScope(project), true).findAll()
        })
        edt(function () { var it = all.iterator(); while (it.hasNext()) add(it.next()) })
    }
    out.sort()
    return out
}
function superMethod(editor, file, dir, result) {
    var handler = com.intellij.lang.CodeInsightActions.GOTO_SUPER.forLanguage(file.getLanguage())
    if (handler == null) { result.value = "<no Go to Super handler>"; return }
    var before = editor.getCaretModel().getOffset()
    edt(function () { handler.invoke(project, editor, file) })
    sleep(1500)
    edt(function () {
        var selected = FileEditorManager.getInstance(project).getSelectedTextEditor() || editor
        var moved = selected.getDocument() != editor.getDocument() || selected.getCaretModel().getOffset() != before
        var vf = FileDocumentManager.getInstance().getFile(selected.getDocument())
        result.value = moved ? lineAt(selected.getDocument(), selected.getCaretModel().getOffset(), path(vf, dir)) : "<no navigation>"
        result.items = [result.value]
        try { com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(editor.getComponent()).forEach(function (p) { p.cancel() }) } catch (x) {}
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file.getVirtualFile(), before), true)
    })
}
function usages(editor, dir) {
    var TEU = com.intellij.codeInsight.TargetElementUtil
    var target = edt(function () { return TEU.findTargetElement(editor, TEU.getInstance().getAllAccepted()) })
    if (target == null) return ["<no target element>"]
    var fm = com.intellij.find.FindManager.getInstance(project).getFindUsagesManager()
    var handler = edt(function () { return fm.getFindUsagesHandler(target, com.intellij.find.findUsages.FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS) })
    if (handler == null) return ["<no find usages handler>"]
    var options = edt(function () { return handler.getFindUsagesOptions(null) })
    options.searchScope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
    var infos = new java.util.ArrayList()
    var processor = new com.intellij.util.Processor({ process: function (u) { infos.add(u); return true } })
    var elements = edt(function () { return handler.getPrimaryElements() })
    for (var i = 0; i < elements.length; i++) handler.processElementUsages(elements[i], processor, options)
    var lines = edt(function () {
        var out = []
        for (var k = 0; k < infos.size(); k++) {
            var u = infos.get(k), e = u.getElement()
            if (e == null) continue
            var doc = PsiDocumentManager.getInstance(project).getDocument(e.getContainingFile())
            if (doc == null) continue
            var r = u.getRangeInElement()
            out.push(lineAt(doc, e.getTextRange().getStartOffset() + (r != null ? r.getStartOffset() : 0), path(e.getContainingFile().getVirtualFile(), dir)))
        }
        return out
    })
    lines.sort()
    return ["count: " + lines.length].concat(lines)
}
function highlightUsages(editor, file) {
    var before = edt(function () { return new java.util.HashSet(java.util.Arrays.asList(editor.getMarkupModel().getAllHighlighters())) })
    edt(function () { com.intellij.codeInsight.highlighting.HighlightUsagesHandler.invoke(project, editor, file) })
    sleep(500)
    return edt(function () {
        var doc = editor.getDocument(), out = [], hs = editor.getMarkupModel().getAllHighlighters()
        var fresh = []
        for (var i = 0; i < hs.length; i++) if (!before.contains(hs[i]) && hs[i].isValid()) fresh.push(hs[i])
        fresh.sort(function (a, b) { return a.getStartOffset() - b.getStartOffset() })
        fresh.forEach(function (h) {
            var key = h.getTextAttributesKey() != null ? String(h.getTextAttributesKey().getExternalName()) : "?"
            if (key.indexOf("IDENTIFIER_UNDER_CARET") >= 0) return
            var sl = doc.getLineNumber(h.getStartOffset()), el = doc.getLineNumber(h.getEndOffset())
            out.push((sl + 1) + ":" + (h.getStartOffset() - doc.getLineStartOffset(sl) + 1) + "-" + (el + 1) + ":" + (h.getEndOffset() - doc.getLineStartOffset(el) + 1) +
                " '" + doc.getText(new TextRange(h.getStartOffset(), h.getEndOffset())) + "' " + key)
        })
        // a second Ctrl+Shift+F7 clears them
        try { com.intellij.codeInsight.highlighting.HighlightUsagesHandler.invoke(project, editor, file) } catch (x) {}
        return out
    })
}
function structure(editor, file) {
    return edt(function () {
        var b = com.intellij.lang.LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file)
        if (b == null || !(b instanceof com.intellij.ide.structureView.TreeBasedStructureViewBuilder)) return ["<no tree structure view builder>"]
        var model = b.createStructureViewModel(editor), out = []
        function walk(el, depth) {
            var p = el.getPresentation(), pad = ""
            for (var k = 0; k < depth; k++) pad += "  "
            var t = String(p.getPresentableText() || "")
            var loc = p.getLocationString()
            if (loc != null && String(loc).trim().length > 0) t += " (" + loc + ")"
            out.push(pad + t)
            var ch = el.getChildren()
            for (var i = 0; i < ch.length; i++) walk(ch[i], depth + 1)
        }
        try { var ch = model.getRoot().getChildren(); for (var i = 0; i < ch.length; i++) walk(ch[i], 0) } finally { model.dispose() }
        return out
    })
}
function parameterInfo(editor, file) {
    return edt(function () {
        var offset = editor.getCaretModel().getOffset()
        var at = file.findElementAt(offset) || file.findElementAt(offset - 1)
        // varargs Language...: an explicit Java array (Rhino does not turn a JS array into one reliably)
        var langs = java.lang.reflect.Array.newInstance(com.intellij.lang.Language, 2)
        langs[0] = at != null ? at.getLanguage() : file.getLanguage()
        langs[1] = file.getLanguage()
        var handlers = com.intellij.codeInsight.hint.ShowParameterInfoHandler.getHandlers(project, langs)
        var out = []
        for (var h = 0; h < handlers.length && out.length == 0; h++) {
            var handler = handlers[h]
            var created = { items: null, highlighted: null }
            var create = new com.intellij.lang.parameterInfo.CreateParameterInfoContext({
                getProject: function () { return project }, getFile: function () { return file }, getOffset: function () { return offset },
                getEditor: function () { return editor }, getParameterListStart: function () { return offset },
                getItemsToShow: function () { return created.items }, setItemsToShow: function (i) { created.items = i },
                showHint: function () {}, getHighlightedElement: function () { return created.highlighted }, setHighlightedElement: function (e) { created.highlighted = e }
            })
            var owner = handler.findElementForParameterInfo(create)
            if (owner == null) continue
            var items = created.items || java.lang.reflect.Array.newInstance(java.lang.Object, 0)
            var state = { current: -1, owner: owner, highlighted: null, enabled: {} }
            var custom = new com.intellij.openapi.util.UserDataHolderBase()
            var update = new com.intellij.lang.parameterInfo.UpdateParameterInfoContext({
                getProject: function () { return project }, getFile: function () { return file }, getOffset: function () { return offset },
                getEditor: function () { return editor }, getParameterListStart: function () { return offset }, removeHint: function () {},
                setParameterOwner: function (o) { state.owner = o }, getParameterOwner: function () { return state.owner },
                setHighlightedParameter: function (p) { state.highlighted = p }, getHighlightedParameter: function () { return state.highlighted },
                setCurrentParameter: function (i) { state.current = i }, getCurrentParameter: function () { return state.current },
                isUIComponentEnabled: function (i) { return state.enabled[i] !== false }, setUIComponentEnabled: function (i, b) { state.enabled[i] = b },
                getObjectsToView: function () { return items }, isPreservedOnHintHidden: function () { return false }, setPreservedOnHintHidden: function () {},
                isInnermostContext: function () { return false }, isSingleParameterInfo: function () { return false }, getCustomContext: function () { return custom }
            })
            var updOwner = handler.findElementForUpdatingParameterInfo(update)
            handler.updateParameterInfo(updOwner != null ? updOwner : owner, update)
            out.push("handler: " + handler.getClass().getSimpleName() + ", current parameter: " + state.current)
            for (var k = 0; k < items.length; k++) {
                var ui = { text: null, hs: -1, he: -1 }
                var uiCtx = new com.intellij.lang.parameterInfo.ParameterInfoUIContextEx({
                    setupUIComponentPresentation: function () {
                        var a = arguments
                        if (a.length == 3 && a[0] != null && typeof a[0] != "string" && a[0].length !== undefined && typeof a[0].length == "number") {
                            // (String[] texts, EnumSet<Flag>[] flags, Color): highlighted pieces carry Flag.HIGHLIGHT
                            var t = "", hs = -1, he = -1
                            for (var q = 0; q < a[0].length; q++) {
                                var hl = a[1] != null && a[1][q] != null && a[1][q].contains(com.intellij.lang.parameterInfo.ParameterInfoUIContextEx.Flag.HIGHLIGHT)
                                if (hl && hs < 0) hs = t.length
                                t += String(a[0][q])
                                if (hl) he = t.length
                            }
                            ui.text = t; ui.hs = hs; ui.he = he
                            return t
                        }
                        ui.text = String(a[0]); ui.hs = a[1]; ui.he = a[2]
                        return ui.text
                    },
                    setupRawUIComponentPresentation: function (html) { ui.text = plainText(html) },
                    isUIComponentEnabled: function () { return true }, setUIComponentEnabled: function () {},
                    getCurrentParameterIndex: function () { return state.current }, getParameterOwner: function () { return owner },
                    isSingleOverload: function () { return items.length == 1 }, isSingleParameterInfo: function () { return false },
                    getDefaultParameterColor: function () { return java.awt.Color.WHITE }, setEscapeFunction: function () {}
                })
                handler.updateUI(items[k], uiCtx)
                var t = ui.text == null ? "<no text>" : ui.text
                out.push(ui.hs >= 0 && ui.he > ui.hs && ui.he <= t.length ? t.substring(0, ui.hs) + "[[" + t.substring(ui.hs, ui.he) + "]]" + t.substring(ui.he) : t)
            }
        }
        return out.length ? out : ["<no parameter info>"]
    })
}
function quickDoc(editor, file, result) {
    var pointers = edt(function () {
        var ts = com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider.getInstance(project).documentationTargets(editor, file, editor.getCaretModel().getOffset())
        var ps = []
        for (var i = 0; i < ts.size(); i++) ps.push(ts.get(i).createPointer())
        return ps
    })
    var html = null
    for (var i = 0; i < pointers.length && html == null; i++) {
        try {
            var data = com.intellij.platform.backend.documentation.impl.ImplKt.computeDocumentationBlocking(pointers[i])
            if (data != null) html = String(data.getHtml())
        } catch (e) { result.notes.push("computeDocumentationBlocking: " + e) }
    }
    if (html == null) {
        // the older provider API
        html = edt(function () {
            var TEU = com.intellij.codeInsight.TargetElementUtil
            var e = TEU.findTargetElement(editor, TEU.getInstance().getAllAccepted())
            if (e == null) return null
            var p = com.intellij.lang.LanguageDocumentation.INSTANCE.forLanguage(file.getLanguage())
            var d = p != null ? p.generateDoc(e, file.findElementAt(editor.getCaretModel().getOffset())) : null
            return d == null ? null : String(d)
        })
    }
    result.value = html == null ? (pointers.length ? "<empty documentation>" : "<no documentation target>") : plainText(html).substring(0, 300)
    if (html != null) result.items = [html.substring(0, 3000)]
}
function expressionTypes(editor, file) {
    return edt(function () {
        // as ShowExpressionTypeHandler: the adjusted caret offset, then up to two characters back until some provider answers
        var offset = com.intellij.codeInsight.TargetElementUtil.adjustOffset(file, editor.getDocument(), editor.getCaretModel().getOffset())
        var out = []
        for (var j = 0; j < 3 && out.length == 0 && offset - j >= 0; j++) {
            var at = file.findElementAt(offset - j)
            if (at == null) continue
            var providers = com.intellij.lang.LanguageExpressionTypes.INSTANCE.allForLanguage(at.getLanguage())
            for (var i = 0; i < providers.size(); i++) {
                var p = providers.get(i), exprs = p.getExpressionsAt(at)
                for (var k = 0; k < exprs.size(); k++) out.push(exprs.get(k).getText() + " -> " + plainText(p.getInformationHint(exprs.get(k))))
            }
        }
        return out.length ? out : ["<no expression type>"]
    })
}
function rendererText(r) {
    try {
        var lists = r.getPresentationLists()
        var parts = []
        for (var i = 0; i < lists.size(); i++) {
            var es = lists.get(i).getEntries(), t = ""
            for (var k = 0; k < es.length; k++) { try { t += es[k].getText() } catch (x) {} }
            parts.push(t)
        }
        return parts.join(" | ")
    } catch (e) {}
    try { var t = r.getText(); if (t != null) return String(t) } catch (e) {}
    return String(r)
}
function inlays(editor) {
    return edt(function () {
        var doc = editor.getDocument(), m = editor.getInlayModel(), n = doc.getTextLength()
        var all = list(m.getInlineElementsInRange(0, n)).concat(list(m.getAfterLineEndElementsInRange(0, n))).concat(list(m.getBlockElementsInRange(0, n)))
        all.sort(function (a, b) { return a.getOffset() - b.getOffset() })
        return all.map(function (inl) {
            var line = doc.getLineNumber(inl.getOffset())
            return (line + 1) + ":" + (inl.getOffset() - doc.getLineStartOffset(line) + 1) + " " + String(inl.getPlacement().name()).toLowerCase() + " " + rendererText(inl.getRenderer())
        })
    })
}

// ------------------------------------------------------------------ keys

var simple = { "{ENTER}": "EditorEnter", "{TAB}": "EditorTab", "{BACKSPACE}": "EditorBackSpace", "{DELETE}": "EditorDelete", "{ESC}": "EditorEscape",
    "{LEFT}": "EditorLeft", "{RIGHT}": "EditorRight", "{UP}": "EditorUp", "{DOWN}": "EditorDown", "{HOME}": "EditorLineStart", "{END}": "EditorLineEnd" }
var slow = { "ReformatCode": true, "OptimizeImports": true }

function runAction(editor, id) {
    var a = ActionManager.getInstance().getAction(id)
    if (a == null) throw "no action " + id
    LookupManager.getInstance(project).hideActiveLookup()
    ActionManager.getInstance().tryToExecute(a, null, editor.getContentComponent(), "EditorPopup", true)
}
function key(s, editor, file, tok, result) {
    var wait = PAUSE
    edt(function () {
        if (tok.length == 1 || tok == "{SPACE}") {
            var ch = tok.length == 1 ? tok.charAt(0) : " "
            TypedAction.getInstance().actionPerformed(editor, ch, dataContext(editor))
        } else if (simple[tok]) runAction(editor, simple[tok])
        else if (tok == "{SAVE}") { FileDocumentManager.getInstance().saveAllDocuments(); wait = SLOW }
        else if (tok == "{PASTE}") {
            com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(s.paste))
            runAction(editor, "EditorPaste")
        } else if (tok.indexOf("{ACTION:") == 0) {
            var id = tok.substring(8, tok.length - 1)
            runAction(editor, id)
            if (slow[id]) wait = SLOW
        } else if (tok.indexOf("{SURROUND:") == 0) {
            var title = tok.substring(10, tok.length - 1)
            var items = surroundItems(editor, file), pick = null
            items.forEach(function (it) { if (pick == null && it.title == title) pick = it })
            items.forEach(function (it) { if (pick == null && it.title.indexOf(title) == 0) pick = it })
            if (pick == null) throw "no Surround With item '" + title + "' in " + items.map(function (it) { return it.title }).join(", ")
            result.notes.push("surround: applied '" + pick.title + "'")
            ActionUtil.performAction(pick.action, eventFor(pick.action, dataContext(editor)))
        } else throw "unknown key token " + tok
    })
    sleep(wait)
    edt(function () { PsiDocumentManager.getInstance(project).commitAllDocuments() })
}

// ------------------------------------------------------------------ one scenario

function runScenario(s) {
    var result = { id: s.id, category: s.category, title: s.title, query: s.query, text: null, steps: null, items: null, value: null, notes: [], error: null, durationMs: 0 }
    var dir = "typingcmp/" + s.id.toLowerCase()
    var base = DIR + "/" + dir
    var m = markers(s.code)
    var vdir = null, mainFile = null, editor = null, file = null
    try {
        edt(function () {
            write(function () {
                vdir = VfsUtil.createDirectories(base)
                s.extras.forEach(function (x) { VfsUtil.saveText(vdir.findOrCreateChildData(null, x.name), x.text) })
                mainFile = vdir.findOrCreateChildData(null, s.file)
                VfsUtil.saveText(mainFile, m.text)
            })
        })
        smart()
        edt(function () {
            editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, mainFile, m.caret), true)
            editor.getCaretModel().moveToOffset(m.caret)
            if (m.selStart >= 0 && m.selEnd >= 0) editor.getSelectionModel().setSelection(m.selStart, m.selEnd)
            else editor.getSelectionModel().removeSelection()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
            com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
            editor.getContentComponent().requestFocusInWindow()
        })
        sleep(SYNC)
        smart()
        if (s.query == "surround") result.items = edt(function () { return surroundItems(editor, file).map(function (it) { return it.title }) })
        if (s.query == "generate") result.items = edt(function () { return generateItems(editor) })
        var steps = []
        for (var k = 0; k < s.keys.length; k++) {
            key(s, editor, file, s.keys[k], result)
            if (s.steps) steps.push(edt(function () { var t = editor.getSelectionModel().getSelectedText(); return { key: s.keys[k], line: caretLine(editor), selection: t == null ? null : String(t) } }))
        }
        if (s.steps) result.steps = steps
        edt(function () { PsiDocumentManager.getInstance(project).commitAllDocuments(); file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()) })
        smart()
        var q = s.query
        if (q == "declaration") result.items = declaration(editor, dir)
        else if (q == "implementations") result.items = implementations(editor, file, dir)
        else if (q == "super") superMethod(editor, file, dir, result)
        else if (q == "usages") result.items = usages(editor, dir)
        else if (q == "highlight") result.items = highlightUsages(editor, file)
        else if (q == "structure") result.items = structure(editor, file)
        else if (q == "paraminfo") result.items = parameterInfo(editor, file)
        else if (q == "quickdoc") quickDoc(editor, file, result)
        else if (q == "exprtype") result.items = expressionTypes(editor, file)
        else if (q == "inlays") result.items = inlays(editor)
        else if (q != null && q != "surround" && q != "generate") throw "unknown query " + q
        if (q == null || s.keys.length > 0) result.text = edt(function () { return withMarkers(editor) })
    } catch (e) {
        result.error = String(e).substring(0, 300)
        try { if (e.javaException) result.stack = String(e.javaException).substring(0, 500) } catch (x) {}
    } finally {
        try {
            edt(function () {
                LookupManager.getInstance(project).hideActiveLookup()
                try { var ts = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(editor); if (ts != null) ts.gracefullyFinishTemplate() } catch (x) {}
                var fem = FileEditorManager.getInstance(project)
                var open = fem.getOpenFiles()
                for (var i = 0; i < open.length; i++) if (String(open[i].getPath()).indexOf(base + "/") == 0) fem.closeFile(open[i])
                if (vdir != null && vdir.isValid()) write(function () { vdir.delete(null) })
            })
        } catch (e2) { result.notes.push("cleanup: " + e2) }
    }
    return result
}

var results = []
var status = new java.lang.StringBuilder()
for (var i = 0; i < scenarios.length; i++) {
    var s = scenarios[i]
    if (only != null && only.indexOf("," + s.id + ",") < 0) continue
    var t0 = java.lang.System.currentTimeMillis()
    var r = runScenario(s)
    r.durationMs = java.lang.System.currentTimeMillis() - t0
    results.push(r)
    status.append(s.id + (r.error != null ? " ERROR " + r.error : " ok") + "\n")
}
var parent = edt(function () { return LocalFileSystem.getInstance().refreshAndFindFileByPath(DIR + "/typingcmp") })
if (parent != null) edt(function () { if (parent.getChildren().length == 0) write(function () { parent.delete(null) }) })
var json = JSON.stringify({ ide: "goland " + com.intellij.openapi.application.ApplicationInfo.getInstance().getFullVersion(), settings: "", results: results }, null, 1)
java.nio.file.Files.createDirectories(java.nio.file.Paths.get(OUT).getParent())
java.nio.file.Files.write(java.nio.file.Paths.get(OUT), new java.lang.String(json).getBytes("UTF-8"))
"@@@" + results.length + " scenarios, " + results.filter(function (r) { return r.error != null }).length + " errors -> " + OUT + "\n" + status.toString()
