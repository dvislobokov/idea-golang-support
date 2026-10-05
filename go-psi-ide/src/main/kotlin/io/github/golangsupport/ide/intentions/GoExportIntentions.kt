package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.refactoring.GoInlineFunction
import io.github.golangsupport.ide.refactoring.GoInlineSupport
import io.github.golangsupport.ide.refactoring.GoTextEdit
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType

/** Base of the intentions that run a refactoring over the project (no write action of their own, no preview). */
abstract class GoRefactoringIntention : IntentionAction {
    override fun getFamilyName(): String = text

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file is GoFile && GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project) && available(file, editor.caretModel.offset)

    protected abstract fun available(file: GoFile, offset: Int): Boolean

    /** The identifier at [offset] (or right before it). */
    protected fun identifierAt(file: GoFile, offset: Int): PsiElement? =
        listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null).firstOrNull { it.node.elementType == GoTypes.IDENTIFIER }
}

/**
 * Export: the name of a package-level function, type, variable or constant, a method or a struct field at the caret gets an upper-case
 * first letter through the platform rename (every usage follows). Not for `init` / `main`, local names, or when the exported name is
 * taken (in the package, or among the fields and methods of the type).
 */
class GoExportIntention : GoRefactoringIntention() {
    override fun getText(): String = "Export"

    override fun available(file: GoFile, offset: Int): Boolean = target(file, offset) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is GoFile) return
        val element = target(file, editor.caretModel.offset) ?: return
        RenameProcessor(project, element, exported(element.name!!), false, false).run()
    }

    private fun exported(name: String): String = name.substring(0, 1).uppercase() + name.substring(1)

    private fun target(file: GoFile, offset: Int): GoNamedElement? {
        val identifier = identifierAt(file, offset) ?: return null
        val element = identifier.parent as? GoNamedElement ?: return null
        if (element.nameIdentifier != identifier) return null
        val name = element.name ?: return null
        if (!name[0].isLetter() || !name[0].isLowerCase()) return null
        val newName = exported(name)
        val service = GoSemanticService.getInstance(file.project)
        return when (element) {
            is GoFunctionDeclaration -> element.takeIf { name != "init" && name != "main" && !takenInPackage(file, newName) }
            is GoTypeSpec -> element.takeIf { topLevel(it) && !takenInPackage(file, newName) }
            is GoVarDefinition, is GoConstDefinition -> element.takeIf { GoPsiTopLevel.isPackageLevel(it) && !takenInPackage(file, newName) }
            is GoMethodDeclaration -> {
                val receiver = element.receiver?.let(service::declarationType) ?: return null
                val named = ((receiver as? GoPointerType)?.elem ?: receiver) as? GoNamedType ?: return null
                element.takeIf { service.lookupFieldOrMethod(named, newName) == null }
            }
            is GoFieldDefinition -> {
                val spec = PsiTreeUtil.getParentOfType(element, GoTypeSpec::class.java)
                val struct = PsiTreeUtil.getParentOfType(element, GoStructType::class.java) ?: return null
                val named = spec?.let(service::declarationType) as? GoNamedType
                if (named != null && PsiTreeUtil.getParentOfType(struct, GoStructType::class.java) == null) {
                    element.takeIf { service.lookupFieldOrMethod(named, newName) == null }
                } else element.takeIf { struct.fieldDeclarationList.none { d -> d.fieldDefinitionList.any { it.name == newName } } }
            }
            else -> null
        }
    }

    private fun takenInPackage(file: GoFile, name: String): Boolean = GoPackageModel.getInstance(file.project).scopeOf(file).lookup(name).isNotEmpty()

    private fun topLevel(spec: GoTypeSpec): Boolean = (spec.parent as? GoTypeDeclaration)?.parent is GoFile
}

/** Package-level declarations. */
internal object GoPsiTopLevel {
    /** Whether [definition] (a var / const definition) is declared at package level, outside any function. */
    fun isPackageLevel(definition: PsiElement): Boolean = PsiTreeUtil.getParentOfType(definition, GoBlock::class.java, GoFile::class.java) is GoFile
}

/**
 * Migrate function parameter to method receiver: `func f(t *T, x int)` → `func (t *T) f(x int)`, every call `f(v, 1)` → `v.f(1)`
 * (`f(&v, 1)` → `v.f(1)`), a function value `f` → the method expression `(*T).f`. Only for a named, non-generic, non-interface type
 * `T` of the same package without a field or method of that name, and a non-generic function. A call the rewrite cannot keep
 * (a multi-value argument, `nil` receiver, a nested call in the first argument) stops it with a hint.
 */
class GoMigrateParameterToReceiverIntention : GoRefactoringIntention() {
    private val title = "Migrate Function Parameter to Method Receiver"

    override fun getText(): String = "Migrate function parameter to method receiver"

    private class Target(val function: GoFunctionDeclaration, val name: String, val typeName: String, val pointer: Boolean)

    private class Refusal(message: String) : RuntimeException(message)

    override fun available(file: GoFile, offset: Int): Boolean = target(file, offset) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is GoFile) return
        val target = target(file, editor.caretModel.offset) ?: return
        val edits = try {
            plan(target)
        } catch (e: Refusal) {
            CommonRefactoringUtil.showErrorHint(project, editor, e.message!!, title, null)
            return
        }
        GoInlineSupport.apply(project, title, edits)
    }

    private fun target(file: GoFile, offset: Int): Target? {
        var e: PsiElement? = GoIntentionText.leafAt(file, offset)
        while (e != null && e !is GoFunctionDeclaration) {
            if (e is GoBlock || e is PsiFile) return null
            e = e.parent
        }
        val function = e as? GoFunctionDeclaration ?: return null
        val name = function.name ?: return null
        if (function.typeParameters != null || name == "init" || name == "main" && file.packageName == "main") return null
        val first = function.signature?.parameters?.parameterDeclarationList?.firstOrNull() ?: return null
        val definition = first.paramDefinitionList.firstOrNull() ?: return null
        if (first.text.contains("...")) return null
        val service = GoSemanticService.getInstance(file.project)
        val type = service.declarationType(definition)
        val pointer = type is GoPointerType
        val named = ((type as? GoPointerType)?.elem ?: type) as? GoNamedType ?: return null
        val spec = named.declaration
        if (named.isInstantiated || spec.typeParameters != null || (spec.parent as? GoTypeDeclaration)?.parent !is GoFile) return null
        if (!GoSourceText(file).isOwnPackage(spec)) return null
        val underlying = named.underlying()
        if (underlying is GoInterfaceType || underlying is GoPointerType) return null
        if (service.lookupFieldOrMethod(named, name) != null) return null
        return Target(function, name, named.name, pointer)
    }

    private fun plan(target: Target): List<GoTextEdit> {
        val function = target.function
        val file = function.containingFile
        val parameters = function.signature!!.parameters.parameterDeclarationList
        val first = parameters.first()
        val definitions = first.paramDefinitionList
        val edits = ArrayList<GoTextEdit>()
        val func = function.func
        edits += GoTextEdit(file, TextRange(func.textRange.endOffset, func.textRange.endOffset), " (${definitions.first().text} ${first.type!!.text})")
        val removed = when {
            definitions.size > 1 -> TextRange(definitions[0].textRange.startOffset, definitions[1].textRange.startOffset)
            parameters.size == 1 -> first.textRange
            else -> GoInlineSupport.listItemRange(first, parameters)
        }
        edits += GoTextEdit(file, removed, "")
        val refs = ReferencesSearch.search(function, GlobalSearchScope.projectScope(function.project)).findAll().mapNotNull { it.element as? GoReferenceExpression }
        val receivers = ArrayList<Pair<PsiFile, TextRange>>()
        for (ref in refs) {
            val call = GoInlineFunction.callOf(ref)
            if (call == null) {
                val refFile = ref.containingFile as? GoFile ?: throw Refusal("'${target.name}' is used outside Go code")
                if (!GoSourceText(refFile).isOwnPackage(function)) throw Refusal("'${target.name}' is used as a value in another package")
                val type = if (target.pointer) "(*${target.typeName})" else target.typeName
                edits += GoTextEdit(refFile, ref.textRange, "$type.${target.name}")
                continue
            }
            val list = call.argumentList ?: throw Refusal("A call of '${target.name}' has no arguments")
            val args = list.expressions
            val receiver = args.firstOrNull() ?: throw Refusal("A call of '${target.name}' has no arguments")
            if (list.hasEllipsis && args.size == 1) throw Refusal("A call of '${target.name}' spreads a slice into the receiver")
            if (args.size == 1 && parameters.sumOf { it.paramDefinitionList.size.coerceAtLeast(1) } > 1) throw Refusal("A call of '${target.name}' passes a multi-value call")
            if (receiver is GoReferenceExpression && receiver.expression == null && receiver.text == "nil") throw Refusal("A call of '${target.name}' passes nil as the receiver")
            val inner = (receiver as? GoUnaryExpr)?.takeIf { target.pointer && it.and != null && it.expression !is GoCompositeLit }?.expression
            val operand = inner ?: receiver
            val text = if (operand is GoCompositeLit || GoInlineSupport.precedence(operand) < 7) "(${operand.text})" else operand.text
            val end = if (args.size > 1) args[1].textRange.startOffset else list.rparen?.textRange?.startOffset ?: throw Refusal("A call of '${target.name}' is incomplete")
            edits += GoTextEdit(call.containingFile, TextRange(call.textRange.startOffset, end), "$text.${target.name}(")
            receivers += call.containingFile to receiver.textRange
        }
        if (refs.any { ref -> receivers.any { (f, r) -> f == ref.containingFile && r.contains(ref.textRange) } }) throw Refusal("A call of '${target.name}' is nested in the receiver argument of another one")
        return edits
    }
}
