// Presses the button of the open modal dialog whose text contains __TEXT__ (a click of the robot does not reach a modal dialog);
// prints the texts of the dialog's buttons and what was pressed. The press is posted (invokeLater, in the modality of the button: with
// ModalityState.any() a refactoring started by it runs in a write-unsafe context, TransactionGuard SEVERE): it may open the next modal dialog.
var out = "?"
com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var buttons = [], pressed = null
    function walk(c) {
        if (c instanceof javax.swing.AbstractButton && c.isShowing() && ("" + c.getText()).length > 0) {
            buttons.push("" + c.getText())
            if (pressed == null && ("" + c.getText()).indexOf("__TEXT__") >= 0) pressed = c
        }
        if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) walk(ch[i]) }
    }
    var ws = java.awt.Window.getWindows()
    for (var j = 0; j < ws.length; j++) if (ws[j].isShowing() && ws[j] instanceof java.awt.Dialog) walk(ws[j])
    out = "buttons: " + buttons.join(" | ") + "; pressed: " + (pressed == null ? "none" : pressed.getText())
    if (pressed != null) {
        var button = pressed
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { button.doClick() } }), com.intellij.openapi.application.ModalityState.stateForComponent(button))
    }
} }), com.intellij.openapi.application.ModalityState.any())
out
