// Presses OK of every open DialogWrapper titled __TITLE__ (part of the title) on EDT: clicks of the AWT robot do not reach a modal
// dialog in an RDP session (seen live with the Equal Method dialog).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.ui.DialogWrapper)
importClass(java.awt.Window)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var done = new CompletableFuture()
// A modal dialog runs its own event loop: only runnables queued with ModalityState.any() get through (editop.js waits forever meanwhile).
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var out = []
        var windows = Window.getWindows()
        for (var i = 0; i < windows.length; i++) {
            if (!windows[i].isShowing() || !(windows[i] instanceof com.intellij.openapi.ui.DialogWrapperDialog)) continue
            var wrapper = windows[i].getDialogWrapper()
            var title = String(wrapper.getTitle())
            if (title.indexOf("__TITLE__") < 0) continue
            wrapper.close(DialogWrapper.OK_EXIT_CODE)
            out.push("OK: " + title)
        }
        done.complete(out.length ? out.join(", ") : "no dialog titled __TITLE__")
    } catch (e) { done.complete("failed: " + e) }
} }), ModalityState.any())
done.get(20, TimeUnit.SECONDS)
