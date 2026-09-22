// Checks of block 1 of PLAN.md in the file of the selected editor, which must be a scratch copy: the text is changed.
// 1. Enter at the end of the line that contains __AFTER__, then typing `x()`: the new line shows the indent.
// 2. Enter between the brackets of a `{}` appended to that: the pair opens into three lines.
// 3. Spoils the formatting and saves: format on save brings it back.
// 4. Creates a test file from the "Go Test" template in the directory of the file.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.CommandProcessor)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.editor.actionSystem.EditorActionManager)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.ide.fileTemplates.FileTemplateManager)
importClass(com.intellij.ide.fileTemplates.FileTemplateUtil)
importClass(com.intellij.psi.PsiManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const result = new CompletableFuture()

function visible(text) { return String(text).split("\t").join("<TAB>").split("\n").join("<NL>\n") }

ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        const document = editor.getDocument()
        const file = FileDocumentManager.getInstance().getFile(document)
        const enter = EditorActionManager.getInstance().getActionHandler("EditorEnter")
        const context = DataManager.getInstance().getDataContext(editor.getContentComponent())
        function pressEnter() { CommandProcessor.getInstance().executeCommand(project, new java.lang.Runnable({ run: function () { enter.execute(editor, editor.getCaretModel().getCurrentCaret(), context) } }), "Enter", null) }
        function type(text) { WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
            document.insertString(editor.getCaretModel().getOffset(), text); editor.getCaretModel().moveToOffset(editor.getCaretModel().getOffset() + text.length) } })) }
        let out = ""

        const at = String(document.getText()).indexOf("__AFTER__")
        const line = document.getLineNumber(at)
        editor.getCaretModel().moveToOffset(document.getLineEndOffset(line))
        pressEnter(); type("x()")
        out += "1. after Enter: [" + visible(document.getText().substring(document.getLineStartOffset(line), document.getLineEndOffset(line + 1))) + "]\n"

        pressEnter(); type("if x {}"); editor.getCaretModel().moveToOffset(editor.getCaretModel().getOffset() - 1)
        pressEnter()
        const caretLine = document.getLineNumber(editor.getCaretModel().getOffset())
        out += "2. between brackets: [" + visible(document.getText().substring(document.getLineStartOffset(caretLine - 1), document.getLineEndOffset(caretLine + 1))) + "] caret column " +
            (editor.getCaretModel().getOffset() - document.getLineStartOffset(caretLine)) + "\n"

        // undo the experiments of 1 and 2 by hand: the file has to compile for gofmt
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
            document.deleteString(document.getLineStartOffset(line + 1), document.getLineEndOffset(caretLine + 1) + 1)
            const spoil = String(document.getText()).indexOf("func ")
            document.insertString(spoil + 4, "     ")
        } }))
        out += "3. spoiled: [" + visible(document.getText().substring(document.getLineStartOffset(document.getLineNumber(String(document.getText()).indexOf("func "))), document.getLineEndOffset(document.getLineNumber(String(document.getText()).indexOf("func "))))) + "]\n"
        FileDocumentManager.getInstance().saveAllDocuments()
        const funcLine = document.getLineNumber(String(document.getText()).indexOf("func "))
        out += "   saved:   [" + visible(document.getText().substring(document.getLineStartOffset(funcLine), document.getLineEndOffset(funcLine))) + "]\n"

        const directory = PsiManager.getInstance(project).findDirectory(file.getParent())
        const template = FileTemplateManager.getInstance(project).getInternalTemplate("Go Test")
        const existing = directory.findFile("order_service_test.go")
        if (existing != null) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { existing.delete() } }))
        const created = FileTemplateUtil.createFromTemplate(template, "order_service_test", null, directory)
        out += "4. template:\n" + visible(created.getContainingFile().getText()) + "\n"
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { created.delete() } }))
        result.complete(out)
    } catch (e) { result.complete("failed: " + e + (e.stack ? "\n" + e.stack : "")) }
} }))
result.get(60, TimeUnit.SECONDS)
