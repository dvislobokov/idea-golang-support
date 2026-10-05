package io.github.golangsupport.lang

import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.icons.AllIcons
import com.intellij.ide.util.MemberChooser
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.intentions.GoPromotedMethod
import io.github.golangsupport.ide.intentions.GoPromotedMethods
import io.github.golangsupport.lang.psi.GoFile
import org.jetbrains.annotations.TestOnly

/**
 * Ctrl+O (Code | Override Methods) in a Go file: the methods a struct gets from its embedded fields (structs or interfaces, pointers or
 * not, of any package) that it does not declare itself, chosen in the platform's member chooser grouped by field, are written after the
 * type's last method as wrappers that call the embedded field: `func (s *Server) Close() error { return s.Conn.Close() }`.
 */
class GoOverrideMethodsHandler : LanguageCodeInsightActionHandler {

    override fun isValidFor(editor: Editor, file: PsiFile): Boolean {
        if (file !is GoFile || DumbService.isDumb(file.project)) return false
        val spec = GenerateContext(file.project, editor, file).typeSpecAtCaret ?: return false
        return GoPromotedMethods.of(spec)?.methods?.isNotEmpty() == true
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (file !is GoFile) return
        val context = GenerateContext(project, editor, file)
        PsiDocumentManager.getInstance(project).commitDocument(context.document)
        val type = context.typeAtCaret ?: return context.hint("Put the caret inside a struct type")
        val plan = context.typeSpecAtCaret?.let(GoPromotedMethods::of)
        if (plan == null || plan.methods.isEmpty()) return context.hint("${type.name} has no promoted methods to override")
        val chosen = choose(project, plan.methods)
        if (chosen.isNullOrEmpty()) return
        val code = chosen.joinToString("\n") { GoGenerators.delegatingMethod(plan.typeName, plan.receiver, plan.pointer, it.field, it.name, it.signature) }
        WriteCommandAction.runWriteCommandAction(project, TITLE, null, {
            context.insertAfter(type, code, TITLE)
            // the imports go above: the caret, put on the methods already, moves with the text
            for (path in chosen.flatMap { it.imports }.distinct()) GoImports.add(context.document.immutableCharSequence, path)?.let { context.document.insertString(it.offset, it.text) }
            PsiDocumentManager.getInstance(project).commitDocument(context.document)
        }, file)
    }

    override fun startInWriteAction(): Boolean = false

    private fun choose(project: Project, methods: List<GoPromotedMethod>): List<GoPromotedMethod>? {
        chooser?.let { return it(methods) }
        if (ApplicationManager.getApplication().isUnitTestMode) return methods
        val members = methods.map(::Member)
        val dialog = MemberChooser(members.toTypedArray(), false, true, project)
        dialog.title = "Select Methods to Override"
        dialog.setCopyJavadocVisible(false)
        if (!dialog.showAndGet()) return null
        return dialog.selectedElements?.map { it.method }
    }

    /** A method in the chooser: `Close() error` under its embedded field. */
    private class Member(val method: GoPromotedMethod) : MemberChooserObjectBase(method.name + method.signature, AllIcons.Nodes.Method), ClassMember {
        override fun getParentNodeDelegate(): MemberChooserObject = Field(method.field, method.owner)
        override fun equals(other: Any?): Boolean = other is Member && other.method.name == method.name && other.method.field == method.field
        override fun hashCode(): Int = method.name.hashCode() * 31 + method.field.hashCode()
    }

    /** The embedded field the methods come through; the type that declares them when it is another one. */
    private class Field(val name: String, owner: String) : MemberChooserObjectBase(if (owner == name) name else "$name ($owner)", AllIcons.Nodes.Field) {
        override fun equals(other: Any?): Boolean = other is Field && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }

    companion object {
        const val TITLE: String = "Override Methods"

        /** What the chooser answers in tests (the promoted methods in, the chosen out); null: all of them. */
        @TestOnly
        @JvmStatic
        var chooser: ((List<GoPromotedMethod>) -> List<GoPromotedMethod>)? = null
    }
}
