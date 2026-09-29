// The popups and dialogs that are open now: their titles, lists (rows), labels and trees; then closes them with Escape when __CLOSE__ is yes.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(java.awt.Window)
importClass(java.awt.event.KeyEvent)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var out = ""
    var windows = Window.getWindows()
    function texts(c, depth) {
        var t = ""
        if (c instanceof javax.swing.JList) { var m = c.getModel(); for (var i = 0; i < m.getSize(); i++) t += "    row: " + m.getElementAt(i) + "\n" }
        if (c instanceof javax.swing.JLabel && c.getText() != null && String(c.getText()).length > 0) t += "    label: " + c.getText() + "\n"
        if (c instanceof javax.swing.JTree) { for (var i = 0; i < c.getRowCount(); i++) t += "    tree: " + c.getPathForRow(i).getLastPathComponent() + "\n" }
        if (c instanceof java.awt.Container && depth < 25) { var kids = c.getComponents(); for (var i = 0; i < kids.length; i++) t += texts(kids[i], depth + 1) }
        return t
    }
    for (var w = 0; w < windows.length; w++) {
        var window = windows[w]
        if (!window.isShowing() || window instanceof javax.swing.JFrame) continue
        out += window.getClass().getSimpleName() + (window instanceof javax.swing.JDialog ? " '" + window.getTitle() + "'" : "") + " " + window.getWidth() + "x" + window.getHeight() + "\n" + texts(window, 0)
    }
    if ("__CLOSE__" == "yes") for (var w = 0; w < windows.length; w++) if (windows[w].isShowing() && !(windows[w] instanceof javax.swing.JFrame)) {
        var focus = windows[w].getMostRecentFocusOwner() || windows[w]
        focus.dispatchEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, java.lang.System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, "\u001b"))
        focus.dispatchEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, java.lang.System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, "\u001b"))
    }
    done.complete(out == "" ? "no popups" : out)
// any modality: a modal dialog is what is being looked for
} }), ModalityState.any())
done.get(30, TimeUnit.SECONDS)
