package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorBase
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandlerRegistry
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSwitchGuard
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.recvStatement
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse

/**
 * GoLand's "Shadowing variable" (`GoShadowedVar`): a local variable (`:=`, `var` in a body, `if` / `for` / `switch` header, range,
 * type-switch guard, `select` receive) whose name is already visible from an enclosing scope as a variable, constant, parameter,
 * result or receiver of the function (or an enclosing one), or as a package-level variable / constant of the same package.
 * A `:=` that reuses a variable of its own scope is not a declaration; parameters and results are not reported; `x := x` and
 * `switch x := x.(type)` are the usual idioms and stay quiet (as in vet's `shadow`). The name is painted with `GO_SHADOWING_VARIABLE`.
 * A scope lookup ([GoScopes.resolveName] from just outside the declaration's scope), not a flow analysis ([flow.GoShadowedErrorInspection]).
 */
class GoShadowingVariableInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoVarDefinition) return
        val shadowed = shadowedBy(element) ?: return
        val name = element.name ?: return
        val anchor = element.nameIdentifier ?: element
        val message = "Declaration of '$name' shadows declaration at ${location(shadowed, file)}"
        val pointers = SmartPointerManager.getInstance(file.project)
        val fixes = arrayOf<LocalQuickFix>(GoNavigateToShadowedFix(pointers.createSmartPsiElementPointer(shadowed)), GoRenameVariableFix())
        val descriptor = holder.manager.createProblemDescriptor(anchor, message, holder.isOnTheFly, fixes, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        // The key is defined by the colour settings page; `find` returns it (or a key of that name before the page registers it).
        (descriptor as? ProblemDescriptorBase)?.setTextAttributes(SHADOWING_VARIABLE)
        holder.registerProblem(descriptor)
    }

    companion object {
        private val SHADOWING_VARIABLE: TextAttributesKey get() = TextAttributesKey.find("GO_SHADOWING_VARIABLE")

        /** The declaration [def] shadows, or null (not a fresh local, nothing visible outside, an idiom, a non-variable). */
        fun shadowedBy(def: GoVarDefinition): GoNamedElement? {
            val name = def.name ?: return null
            if (name == "_" || GoPsiUtil.functionOwner(def) == null) return null
            val lookupFrom = lookupPlace(def, name) ?: return null
            val target = GoScopes.resolveName(lookupFrom, name).firstNotNullOfOrNull { (it as? GoScopes.Target.Declaration)?.element } ?: return null
            if (target === def || !isVariableLike(target) || GoUniverse.isBuiltinDeclaration(target)) return null
            // Package level: only the same package (a dot import is another package's name, not a shadowed declaration).
            if (GoPsiUtil.functionOwner(target) == null && target.containingFile?.containingDirectory != def.containingFile?.containingDirectory) return null
            return target
        }

        private fun isVariableLike(e: GoNamedElement): Boolean = e is GoVarDefinition || e is GoConstDefinition || e is GoParamDefinition || e is GoReceiver

        /**
         * Where the name is looked up: the element whose parent chain starts just outside [def]'s scope. Null when [def] reuses a
         * variable of its own scope (`a, err := …` after `b, err := …`) or is an idiomatic re-binding.
         */
        private fun lookupPlace(def: GoVarDefinition, name: String): PsiElement? = when (val p = def.parent) {
            is GoRangeClause -> p.parent
            is GoRecvStatement -> PsiTreeUtil.getParentOfType(p, GoCommClause::class.java)
            is GoTypeSwitchGuard -> if (isSameName(p.expression, name)) null else p.parent
            is GoShortVarDeclaration -> if (isSelfBinding(def, p.varDefinitionList, p.expressionList, name)) null else statementPlace(p, def, name)
            is GoVarSpec -> if (isSelfBinding(def, p.varDefinitionList, p.expressionList, name)) null else (p.parent as? GoVarDeclaration)?.let { statementPlace(it, def, name) }
            else -> null
        }

        private fun statementPlace(statement: PsiElement, def: GoVarDefinition, name: String): PsiElement? {
            var holder = statement
            while (holder.parent is GoSimpleStatement || holder.parent is GoLabeledStatement) holder = holder.parent
            return when (val container = holder.parent) {
                is GoForClause -> container.parent
                is GoIfStatement, is GoExprSwitchStatement, is GoTypeSwitchStatement -> container
                is GoBlock, is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause ->
                    if (reusesOwnScope(container, holder, def, name)) null
                    else container.parent.takeIf { container is GoBlock && (it is GoFunctionOrMethodDeclaration || it is GoFunctionLit) } ?: container
                else -> null
            }
        }

        /** Whether the scope [container] already declares [name] before [holder]: then `:=` assigns, it does not declare. */
        private fun reusesOwnScope(container: PsiElement, holder: PsiElement, def: GoVarDefinition, name: String): Boolean {
            if (container.children.takeWhile { it !== holder }.any { s -> GoPsiUtil.declarationsOf(s).any { it !== def && it.name == name } }) return true
            return when (container) {
                is GoTypeCaseClause -> (container.parent as? GoTypeSwitchStatement)?.guard?.varDefinition?.name == name
                is GoCommClause -> container.recvStatement?.varDefinitionList.orEmpty().any { it.name == name }
                is GoBlock -> when (val owner = container.parent) {
                    is GoMethodDeclaration -> owner.receiver?.name == name || declaresParameter(owner.signature, name)
                    is GoFunctionOrMethodDeclaration -> declaresParameter(owner.signature, name)
                    is GoFunctionLit -> declaresParameter(owner.signature, name)
                    else -> false
                }
                else -> false
            }
        }

        private fun declaresParameter(signature: GoSignature?, name: String): Boolean =
            (signature?.parameters?.parameterDeclarationList.orEmpty() + signature?.result?.parameters?.parameterDeclarationList.orEmpty())
                .any { d -> d.paramDefinitionList.any { it.name == name } }

        /** `x := x` / `var x = x`: the idiomatic copy of an outer variable (loop variables before Go 1.22). */
        private fun isSelfBinding(def: GoVarDefinition, defs: List<GoVarDefinition>, values: List<GoExpression>, name: String): Boolean =
            defs.size == values.size && isSameName(values.getOrNull(defs.indexOf(def)), name)

        private fun isSameName(e: GoExpression?, name: String): Boolean = e is GoReferenceExpression && e.expression == null && e.identifier.text == name

        /** `line N` in the same file, `file.go:N` in another one. */
        fun location(target: GoNamedElement, file: GoFile): String {
            val targetFile = target.containingFile ?: return "line ?"
            val offset = (target.nameIdentifier ?: target).textRange.startOffset
            val document = PsiDocumentManager.getInstance(targetFile.project).getDocument(targetFile)
            val line = (document?.getLineNumber(offset) ?: StringUtil.offsetToLineNumber(targetFile.text, offset)) + 1
            return if (targetFile == file) "line $line" else "${targetFile.name}:$line"
        }
    }
}

/** "Navigate to shadowed declaration": moves the caret to the outer declaration's name. Does not edit, so no write action. */
class GoNavigateToShadowedFix(private val target: SmartPsiElementPointer<GoNamedElement>) : LocalQuickFix {
    override fun getFamilyName(): String = "Navigate to shadowed declaration"

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = target.element ?: return
        val file = element.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, file, (element.nameIdentifier ?: element).textRange.startOffset).navigate(true)
    }
}

/** "Rename variable" (or [text]): the platform's rename (in place for locals) on the declaration at the problem. */
class GoRenameVariableFix(private val text: String = "Rename variable") : LocalQuickFix {
    override fun getFamilyName(): String = text

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = PsiTreeUtil.getParentOfType(descriptor.psiElement, GoNamedElement::class.java, false) ?: return
        val file = element.containingFile ?: return
        val editor = editorOf(project, file) ?: return
        editor.caretModel.moveToOffset((element.nameIdentifier ?: element).textRange.startOffset)
        val context = SimpleDataContext.builder()
            .setParent(DataManager.getInstance().getDataContext(editor.component))
            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor).add(CommonDataKeys.PSI_FILE, file)
            .add(CommonDataKeys.PSI_ELEMENT, element)
            .build()
        val handler = RenameHandlerRegistry.getInstance().getRenameHandler(context)
        if (handler != null) handler.invoke(project, editor, file, context) else PsiElementRenameHandler.invoke(element, project, element, editor)
    }

    private fun editorOf(project: Project, file: com.intellij.psi.PsiFile): Editor? {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val selected = FileEditorManager.getInstance(project).selectedTextEditor
        return selected?.takeIf { it.document == document }
            ?: file.virtualFile?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true) }
    }
}
