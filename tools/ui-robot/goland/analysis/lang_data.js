// Language catalogues through the public platform API (no page scraping): __WHAT__ = inspections | templates | postfix | intentions |
// filetemplates | inlay. __LANG__ is the language id (Go), __GROUP__ the live template group / intention category prefix (Go).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.project.ProjectManager)
var out = new java.lang.StringBuilder("@@@")
var what = "__WHAT__"
function clean(s) { return s == null ? "" : String(s).replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/\s+/g, " ").trim() }
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var project = ps[ps.length - 1]
    if (what == "inspections") {
        var profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project).getCurrentProfile()
        var tools = profile.getAllTools()
        var rows = []
        for (var i = 0; i < tools.size(); i++) {
            var st = tools.get(i)
            var w = st.getTool()
            var lang = String(w.getLanguage() || "")
            var path = ""
            try { path = java.lang.String.join(" | ", w.getGroupPath()) } catch (e) { path = String(w.getGroupDisplayName()) }
            if (lang != "__LANG__" && path.indexOf("__GROUP__") != 0) continue
            var level = st.getLevel().getName()
            rows.push(path + " :: " + w.getDisplayName() + "  {" + w.getShortName() + "}  " + level + (st.isEnabled() ? "" : "  [off]") + (lang ? "" : "  (lang?)"))
        }
        rows.sort()
        out.append("profile " + profile.getName() + ", " + rows.length + " inspections\n")
        for (var i = 0; i < rows.length; i++) out.append(rows[i] + "\n")
    } else if (what == "templates") {
        var ts = com.intellij.codeInsight.template.impl.TemplateSettings.getInstance().getTemplates()
        var types = com.intellij.codeInsight.template.impl.TemplateContextTypes.getAllContextTypes()
        var n = 0
        for (var i = 0; i < ts.length; i++) {
            var t = ts[i]
            if (String(t.getGroupName()).indexOf("__GROUP__") != 0) continue
            var ctx = []
            for (var k = 0; k < types.size(); k++) if (t.getTemplateContext().isEnabled(types.get(k))) ctx.push(types.get(k).getContextId())
            out.append("[" + t.getGroupName() + "] " + t.getKey() + " — " + clean(t.getDescription()) + "  {contexts: " + ctx.join(", ") + "}" + (t.isDeactivated() ? " [off]" : "") + "\n      " + String(t.getString()).replace(/\n/g, "⏎") + "\n")
            n++
        }
        out.append("templates: " + n + "\n")
    } else if (what == "postfix") {
        var lang = com.intellij.lang.Language.findLanguageByID("__LANG__")
        var provs = com.intellij.codeInsight.template.postfix.templates.LanguagePostfixTemplate.LANG_EP.allForLanguage(lang)
        var n = 0
        for (var p = 0; p < provs.size(); p++) {
            var set = provs.get(p).getTemplates()
            var it = set.iterator()
            while (it.hasNext()) {
                var t = it.next()
                out.append("." + String(t.getKey()).replace(/^\./, "") + " — " + clean(t.getDescription()) + "  e.g. " + clean(t.getExample()) + "\n")
                n++
            }
        }
        out.append("postfix templates: " + n + "\n")
    } else if (what == "intentions") {
        var mgr = com.intellij.codeInsight.intention.IntentionManager.getInstance()
        var all = mgr.getAvailableIntentions()
        var meta = com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings.getInstance().getMetaData()
        var n = 0
        var rows = []
        for (var i = 0; i < meta.size(); i++) {
            var m = meta.get(i)
            var cat = java.lang.String.join(" | ", m.myCategory)
            if (cat.indexOf("__GROUP__") != 0) continue
            var on = com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings.getInstance().isEnabled(m)
            rows.push(cat + " :: " + m.getFamily() + (on ? "" : "  [off]"))
        }
        rows.sort()
        for (var i = 0; i < rows.length; i++) out.append(rows[i] + "\n")
        out.append("intentions: " + rows.length + "\n")
    } else if (what == "filetemplates") {
        var ftm = com.intellij.ide.fileTemplates.FileTemplateManager.getInstance(project)
        var groups = [["Files", ftm.getAllTemplates()], ["Internal", ftm.getInternalTemplates()], ["Includes", ftm.getAllPatterns()], ["Code", ftm.getAllCodeTemplates()]]
        for (var g = 0; g < groups.length; g++) {
            var arr = groups[g][1]
            for (var i = 0; i < arr.length; i++) {
                var t = arr[i]
                if (String(t.getExtension()).toLowerCase() != "go" && String(t.getName()).indexOf("Go") < 0) continue
                out.append("[" + groups[g][0] + "] " + t.getName() + "." + t.getExtension() + "\n      " + String(t.getText()).replace(/\n/g, "⏎").substring(0, 400) + "\n")
            }
        }
    } else if (what == "inlay") {
        var lang = com.intellij.lang.Language.findLanguageByID("__LANG__")
        try {
            var dps = com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory.Companion.getProvidersForLanguage(lang)
            out.append("declarative providers: " + dps.size() + "\n")
            for (var i = 0; i < dps.size(); i++) { var d = dps.get(i); out.append("  " + d.getProviderId() + " — " + clean(d.getProviderName()) + " — " + clean(d.getDescription()) + (d.isEnabledByDefault() ? "" : "  [off by default]") + "\n") }
        } catch (e) { out.append("declarative: " + e + "\n") }
        try {
            var ps2 = com.intellij.codeInsight.hints.InlayHintsProviderFactory.EP.getExtensionList()
            for (var i = 0; i < ps2.size(); i++) { var l = ps2.get(i).getProvidersInfoForLanguage(lang); for (var k = 0; k < l.size(); k++) out.append("  (old) " + String(l.get(k).getClass().getName()).replace(/^.*\./, "") + "\n") }
        } catch (e) { out.append("old api: " + e + "\n") }
        try {
            var ph = com.intellij.codeInsight.hints.InlayParameterHintsExtension.INSTANCE.forLanguage(lang)
            if (ph != null) {
                out.append("parameter hints provider: " + ph.getClass().getName() + "\n")
                var opts = ph.getSupportedOptions()
                for (var i = 0; i < opts.size(); i++) out.append("  option " + clean(opts.get(i).getName()) + " = " + opts.get(i).get() + "\n")
                var bl = ph.getDefaultBlackList()
                out.append("  default blacklist: " + bl.size() + " — " + String(java.lang.String.join("; ", bl)).substring(0, 600) + "\n")
            }
        } catch (e) { out.append("param hints: " + e + "\n") }
        try {
            var cv = com.intellij.codeInsight.codeVision.settings.CodeVisionSettings.Companion.getInstance()
            out.append("code vision enabled: " + cv.getCodeVisionEnabled() + "\n")
            var provs = com.intellij.codeInsight.codeVision.CodeVisionProviderFactory.Companion.createAllProviders(project)
            for (var i = 0; i < provs.size(); i++) { var pv = provs.get(i); out.append("  cv " + pv.getId() + " — " + clean(pv.getName()) + " enabled=" + cv.isProviderEnabled(pv.getGroupId()) + "\n") }
        } catch (e) { out.append("code vision: " + e + "\n") }
    }
} }), ModalityState.any())
out.toString()
