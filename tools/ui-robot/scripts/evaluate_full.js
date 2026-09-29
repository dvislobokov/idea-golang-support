// Evaluates __EXPRESSION__ in the current frame: the presentation, and whether a full-value evaluator ("View") was offered; if so, what it returns.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.evaluation.XDebuggerEvaluator)
importClass(com.intellij.xdebugger.frame.XValueNode)
importClass(com.intellij.xdebugger.frame.XValuePlace)
importClass(com.intellij.xdebugger.frame.XFullValueEvaluator)
importClass(com.intellij.xdebugger.frame.presentation.XValuePresentation)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var session = XDebuggerManager.getInstance(project).getCurrentSession()
var frame = session.getCurrentStackFrame()
var result = new CompletableFuture()
frame.getEvaluator().evaluate("__EXPRESSION__", new XDebuggerEvaluator.XEvaluationCallback({
    evaluated: function (value) { result.complete(value) },
    errorOccurred: function (message) { result.complete("error: " + message) },
}), null)
var value = result.get(60, TimeUnit.SECONDS)
var text
if (typeof value == "string" || value instanceof java.lang.String) text = String(value)
else {
    var shown = new CompletableFuture()
    var full = new CompletableFuture()
    value.computePresentation(new XValueNode({
        setPresentation: function (icon, a, b, c) {
            var t = ""
            if (c === undefined) a.renderValue(new XValuePresentation.XValueTextRenderer({
                renderValue: function (v) { t += v }, renderStringValue: function (v) { t += v }, renderNumericValue: function (v) { t += v },
                renderKeywordValue: function (v) { t += v }, renderComment: function (v) { t += v }, renderSpecialSymbol: function (v) { t += v }, renderError: function (v) { t += v },
            }))
            else t = String(b)
            shown.complete(t)
        },
        setFullValueEvaluator: function (e) {
            e.startEvaluation(new XFullValueEvaluator.XFullValueEvaluationCallback({ evaluated: function (v) { full.complete(String(v)) }, errorOccurred: function (m) { full.complete("error: " + m) }, isObsolete: function () { return false } }))
        },
        isObsolete: function () { return false },
    }), XValuePlace.TREE)
    var s = String(shown.get(30, TimeUnit.SECONDS))
    text = "shown (" + s.length + " chars): " + s
    java.lang.Thread.sleep(3000)
    if (full.isDone()) { var f = String(full.get(1, TimeUnit.SECONDS)); text += "\nfull (" + f.length + " chars): " + f.substring(0, 120) }
    else text += "\nno full value evaluator"
}
text
