// A Choose by Name popup (Implement Interface, Go to Class…): types __TEXT__ into its field, waits for the list, prints the rows
// with the selected one marked, and presses Enter when __ENTER__ is yes (the first row of __ROW__ text is selected first, if given).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(java.awt.Window)
importClass(java.awt.event.KeyEvent)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)

function find(c, predicate, depth) {
    if (predicate(c)) return c
    if (c instanceof java.awt.Container && depth < 30) { var kids = c.getComponents(); for (var i = 0; i < kids.length; i++) { var f = find(kids[i], predicate, depth + 1); if (f != null) return f } }
    return null
}
function popupWindows() {
    var result = []
    var windows = Window.getWindows()
    for (var w = 0; w < windows.length; w++) if (windows[w].isShowing() && !(windows[w] instanceof javax.swing.JFrame)) result.push(windows[w])
    return result
}
function onEdt(body) {
    var done = new CompletableFuture()
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { try { done.complete(body()) } catch (e) { done.complete("error: " + e) } } }), ModalityState.any())
    return done.get(30, TimeUnit.SECONDS)
}
function key(component, code) {
    component.dispatchEvent(new KeyEvent(component, KeyEvent.KEY_PRESSED, java.lang.System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
    component.dispatchEvent(new KeyEvent(component, KeyEvent.KEY_RELEASED, java.lang.System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
}

var typed = onEdt(function () {
    var windows = popupWindows()
    for (var w = 0; w < windows.length; w++) {
        var field = find(windows[w], function (c) { return c instanceof javax.swing.JTextField }, 0)
        if (field != null) { field.setText("__TEXT__"); return "typed" }
    }
    return "no field"
})
java.lang.Thread.sleep(3000)
var out = typed + "\n" + onEdt(function () {
    var windows = popupWindows()
    var text = ""
    for (var w = 0; w < windows.length; w++) {
        var list = find(windows[w], function (c) { return c instanceof javax.swing.JList }, 0)
        if (list == null) continue
        var m = list.getModel()
        var wanted = "__ROW__"
        if (wanted.length > 0) for (var i = 0; i < m.getSize(); i++) if (String(m.getElementAt(i)).indexOf(wanted) >= 0) { list.setSelectedIndex(i); break }
        for (var i = 0; i < m.getSize(); i++) text += (i == list.getSelectedIndex() ? "  > " : "    ") + m.getElementAt(i) + "\n"
    }
    // Enter is handled by the field of the popup, the list only shows what it finds
    if ("__ENTER__" == "yes") for (var w = 0; w < windows.length; w++) {
        var field = find(windows[w], function (c) { return c instanceof javax.swing.JTextField }, 0)
        if (field != null) key(field, KeyEvent.VK_ENTER)
    }
    return text == "" ? "no list" : text
})
out
