// Code vision lenses (block inlays) and gutter icons of the selected editor: text per line.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.impl.DocumentMarkupModel)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder("@@@")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var doc = editor.getDocument()
    out.append("file " + FileEditorManager.getInstance(project).getSelectedFiles()[0].getName() + "\n")
    function lineText(o) { var l = doc.getLineNumber(o); return (l + 1) + ": " + String(doc.getText(new com.intellij.openapi.util.TextRange(doc.getLineStartOffset(l), doc.getLineEndOffset(l)))).trim().substring(0, 70) }
    var key = com.intellij.codeInsight.codeVision.ui.model.CodeVisionListData.KEY
    var blk = editor.getInlayModel().getBlockElementsInRange(0, doc.getTextLength())
    out.append("##### code vision\n")
    for (var i = 0; i < blk.size(); i++) {
        var d = blk.get(i).getUserData(key)
        var lens = []
        if (d != null) {
            var ent = d.getVisibleLens()
            for (var k = 0; k < ent.size(); k++) lens.push(String(ent.get(k).getLongPresentation()) + " [" + ent.get(k).getProviderId() + "]")
        }
        out.append("  " + lineText(blk.get(i).getOffset()) + "  ==> " + lens.join("  |  ") + "\n")
    }
    // GoLand puts usages / inheritors at the end of the declaration line (InlineCodeVisionInlayRenderer), not above it
    var ale = editor.getInlayModel().getAfterLineEndElementsInRange(0, doc.getTextLength())
    out.append("##### code vision at line end\n")
    for (var i = 0; i < ale.size(); i++) {
        var d2 = ale.get(i).getUserData(key)
        if (d2 == null) continue
        var lens2 = []
        var ent2 = d2.getVisibleLens()
        for (var k = 0; k < ent2.size(); k++) lens2.push(String(ent2.get(k).getLongPresentation()) + " [" + ent2.get(k).getProviderId() + "]")
        out.append("  " + lineText(ale.get(i).getOffset()) + "  ==> " + lens2.join("  |  ") + "\n")
    }
    out.append("##### gutter icons\n")
    var hs = DocumentMarkupModel.forDocument(doc, project, true).getAllHighlighters()
    var hs2 = editor.getMarkupModel().getAllHighlighters()
    var all = []
    for (var i = 0; i < hs.length; i++) all.push(hs[i])
    for (var i = 0; i < hs2.length; i++) all.push(hs2[i])
    for (var i = 0; i < all.length; i++) {
        var g = all[i].getGutterIconRenderer()
        if (g == null) continue
        var t = ""
        try { t = g.getTooltipText() } catch (e) {}
        var a = ""
        try { a = g.getAccessibleName() } catch (e) {}
        var ic = ""
        try { ic = String(g.getIcon()).replace(/^.*[\/\\]/, "").substring(0, 60) } catch (e) {}
        var act = ""
        try { var ca = g.getClickAction(); if (ca != null) act = String(ca.getTemplatePresentation().getText()) } catch (e) {}
        out.append("  " + lineText(all[i].getStartOffset()) + "  ==> tooltip=" + (t ? String(t).replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim().substring(0, 150) : "-") + " acc=" + (a || "-") + " icon=" + ic + (act ? " click=" + act : "") + "\n")
    }
} }), ModalityState.any())
out.toString()
