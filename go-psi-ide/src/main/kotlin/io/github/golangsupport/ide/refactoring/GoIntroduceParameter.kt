package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/** What Introduce Parameter and Introduce Field share: the declarations an expression uses, its type as a declaration spells it. */
internal object GoIntroduceSupport {

    /** [compute] in a read action under a modal progress titled [title]; null when the user cancels it. */
    fun <T : Any> underProgress(project: Project, title: String, compute: () -> T?): T? = try {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<T?, RuntimeException> { ReadAction.compute<T?, RuntimeException> { compute() } }, title, true, project,
        )
    } catch (_: ProcessCanceledException) {
        null
    }

    /** The declarations [expr] names (values and types), not counting those declared inside [expr] itself (a function literal's). */
    fun targets(expr: GoExpression): List<PsiElement> {
        val service = GoSemanticService.getInstance(expr.project)
        val refs = PsiTreeUtil.findChildrenOfType(expr, GoReferenceExpression::class.java) + listOfNotNull(expr as? GoReferenceExpression)
        val types = PsiTreeUtil.findChildrenOfType(expr, GoTypeReferenceExpression::class.java)
        val all = refs.flatMap { service.resolve(it) } + types.mapNotNull { service.resolve(it) }
        return all.filter { !(it.containingFile == expr.containingFile && expr.textRange.contains(it.textRange)) }
    }

    /** Whether [expr] uses a parameter, receiver, local variable, constant or type of [owner]: it cannot be evaluated outside it. */
    fun usesLocals(expr: GoExpression, owner: PsiElement): Boolean =
        targets(expr).any { it.containingFile == owner.containingFile && owner.textRange.contains(it.textRange) }

    /** Unqualified names of [expr] declared at the package level of its file (they do not resolve from another package). */
    fun usesOwnPackage(expr: GoExpression): Boolean {
        val dir = expr.containingFile.originalFile.virtualFile?.parent
        return targets(expr).any { t ->
            val f = t.containingFile as? GoFile
            f != null && f.packageName != "builtin" && f.originalFile.virtualFile?.parent == dir && GoPsiUtil.functionOwner(t) == null
        }
    }

    /** The type a declaration of [expr]'s value gets: an untyped constant takes the type expected where it stands, else its default type. */
    fun declaredType(expr: GoExpression): GoType {
        val service = GoSemanticService.getInstance(expr.project)
        var t = service.typeOf(expr)
        if (GoTypePredicates.isUntyped(t)) t = service.expectedTypeAt(expr)?.takeIf { !GoTypePredicates.isUntyped(it) } ?: GoTypePredicates.defaultType(t)
        return t
    }

    /** Whether [type] names a type declared inside a function: a package-level declaration cannot spell it. */
    fun isLocalType(type: GoType): Boolean = type is GoNamedType && GoPsiUtil.functionOwner(type.declaration) != null

    fun isTuple(type: GoType): Boolean = type is GoTupleType
}

/**
 * Introduce Parameter (Ctrl+Alt+P): the selected expression of a function body becomes a new parameter (last, before a variadic one),
 * the expression (or all its equivalents in the body) is replaced by the parameter, and every call of the function in the project gets
 * the expression as the new argument. The expression must not use the function's locals or parameters (calls could not evaluate it),
 * nor, when the function is called from another package, unqualified names of its own package. The signature and the calls are
 * changed by [GoChangeSignatureProcessor] (methods implementing an interface change with their hierarchy, function values are
 * reported as conflicts), the expression is replaced in the same command.
 */
class GoIntroduceParameterHandler @JvmOverloads constructor(options: GoIntroduceOptions? = null) : GoIntroduceHandlerBase(options) {
    override val title: String = TITLE

    override fun problem(expr: GoExpression): String? {
        GoExtraction.rejectReason(expr)?.let { return it }
        val decl = declarationOf(expr) ?: return "The expression should be inside the body of a function or method"
        val type = GoSemanticService.getInstance(expr.project).typeOf(expr)
        if (GoExtraction.isValueless(type)) return "The expression has no value"
        if (GoIntroduceSupport.isTuple(type)) return "The expression has several values"
        if (GoIntroduceSupport.usesLocals(expr, decl)) return "Expression depends on local variables"
        if (GoChangeSignature.parametersOf(decl.signature).any { it.name.isEmpty() }) return "The parameters of the function have no names"
        return null
    }

    override fun introduce(project: Project, editor: Editor, file: GoFile, expr: GoExpression) {
        val decl = declarationOf(expr) ?: return
        if (!GoImplementations.isInProject(decl)) return error(project, editor, "The function is not in the project")
        val type = GoIntroduceSupport.declaredType(expr)
        if (GoIntroduceSupport.isLocalType(type)) return error(project, editor, "The type of the expression is declared inside the function")
        if (GoIntroduceSupport.usesOwnPackage(expr) && (calledFromOtherPackage(project, decl, file) ?: return)) {
            return error(project, editor, "The expression uses names of package ${file.packageName} and the function is called from another package")
        }
        val typeText = GoSourceText(file).type(type)
        val all = GoExtraction.occurrences(expr, decl.block ?: return) { problem(it) == null }
        chooseOccurrences(editor, expr, all) { chosen ->
            val taken = GoExtraction.localTaken(expr, decl)
            val names = GoExtraction.suggestNames(expr, type).map { GoExtraction.unique(it, taken) }.distinct()
            val name = options?.name ?: names.first()
            perform(project, editor, decl, chosen, name, typeText, expr.text, names)
        }
    }

    private fun perform(project: Project, editor: Editor, decl: GoFunctionOrMethodDeclaration, chosen: List<GoExpression>, name: String, type: String, value: String, names: List<String>) {
        val document = editor.document
        val markers = chosen.map { document.createRangeMarker(it.textRange) }
        val initial = GoChangeSignature.initial(decl)
        val parameters = initial.parameters.toMutableList()
        parameters.add(if (parameters.lastOrNull()?.isVariadic == true) parameters.size - 1 else parameters.size, GoChangeParameter(name, type, -1, value))
        val pointer = SmartPointerManager.createPointer(decl)
        val processor = GoChangeSignatureProcessor(project, decl, initial.copy(parameters = parameters))
        processor.commandTitle = TITLE
        processor.afterRefactoring = {
            val documents = PsiDocumentManager.getInstance(project)
            documents.doPostponedOperationsAndUnblockDocument(document)
            for (m in markers.sortedByDescending { it.startOffset }) if (m.isValid) document.replaceString(m.startOffset, m.endOffset, name)
            documents.commitDocument(document)
        }
        processor.run()
        markers.forEach { it.dispose() }
        val parameter = pointer.element?.let { d -> GoParameterRemoval.definitions(d.signature).firstOrNull { it.name == name } }
        renameInPlace(editor, parameter, names, member = false)
    }

    /**
     * Whether a reference to [decl] stands in another package than [file]'s (searched under a modal progress, not on the EDT itself);
     * null when the user cancels the search.
     */
    private fun calledFromOtherPackage(project: Project, decl: GoFunctionOrMethodDeclaration, file: GoFile): Boolean? {
        val dir = file.originalFile.virtualFile?.parent
        return GoIntroduceSupport.underProgress(project, "Looking for Calls of ${decl.name}") {
            ReferencesSearch.search(decl, decl.useScope).anyMatch { ref ->
                val other = ref.element.containingFile as? GoFile
                other != null && (other.originalFile.virtualFile?.parent != dir || other.packageName != file.packageName)
            }
        }
    }

    companion object {
        const val TITLE: String = "Introduce Parameter"

        /** The function or method whose body holds [expr] (through function literals). */
        fun declarationOf(expr: PsiElement): GoFunctionOrMethodDeclaration? {
            val decl = PsiTreeUtil.getParentOfType(expr, GoFunctionOrMethodDeclaration::class.java) ?: return null
            return decl.takeIf { d -> d.block?.let { PsiTreeUtil.isAncestor(it, expr, true) } == true }
        }
    }
}
