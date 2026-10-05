package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.invertBoolean.InvertBooleanDelegate
import com.intellij.refactoring.invertBoolean.InvertBooleanProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.intentions.GoExpressionText
import io.github.golangsupport.ide.intentions.GoIfText
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.rename.GoNamesValidator
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import javax.swing.JComponent

/**
 * The Go side of the platform's Invert Boolean (Refactor | Invert Boolean…): a boolean variable, parameter or struct field, or a function
 * or method returning one `bool`, is renamed and every value it gets or gives is negated, so the program means the same: the initializer
 * (`var x bool` gets `= true`), assigned values and keyed literal values, the arguments of a parameter at the calls, the `return` values of
 * a function; every read becomes `!x` (`!x` becomes `x`, `x == true` becomes `x == false`, `a && b` follows De Morgan).
 */
internal object GoInvertBoolean {
    const val TITLE: String = "Invert Boolean"

    /** The declaration Invert Boolean works on for the element at the caret (a name, a reference, the declaration itself). */
    fun targetOf(element: PsiElement): GoNamedElement? {
        var e: PsiElement? = element
        if (e != null && e !is GoNamedElement && e !is GoReferenceExpression) e = e.parent
        if (e is GoReferenceExpression) e = GoSemanticService.getInstance(e.project).resolve(e).firstOrNull()
        return when (e) {
            is GoVarDefinition, is GoParamDefinition, is GoFieldDefinition, is GoFunctionOrMethodDeclaration -> e as GoNamedElement
            else -> null
        }
    }

    /** Why [target] cannot be inverted, or null. */
    fun problem(target: GoNamedElement): String? {
        if (!GoImplementations.isInProject(target)) return "${target.name} is not in the project"
        val service = GoSemanticService.getInstance(target.project)
        if (target is GoFunctionOrMethodDeclaration) {
            val results = (service.declarationType(target) as? GoSignatureType)?.results.orEmpty()
            if (results.size != 1 || !isBool(results[0].type)) return "Invert Boolean needs a function returning one bool"
            if (target.block == null) return "The function has no body"
            if (!results[0].name.isNullOrEmpty()) return "The function has a named result"
            return null
        }
        if (target is GoParamDefinition && GoParameterRemoval.ownerOf(target) !is GoFunctionOrMethodDeclaration) return "Only parameters of functions and methods can be inverted"
        if (!isBool(service.declarationType(target))) return "Invert Boolean needs a variable, parameter or field of type bool"
        if (target is GoVarDefinition) {
            val values = initializers(target)
            if (!values.isNullOrEmpty() && values.size != definitions(target).size) return "${target.name} is initialized from a call of several results"
        }
        return null
    }

    private fun isBool(type: GoType?): Boolean = (type?.underlying() as? GoBasicType)?.kind?.isBoolean == true

    private fun definitions(def: GoVarDefinition): List<GoVarDefinition> = when (val p = def.parent) {
        is GoVarSpec -> p.varDefinitionList
        is GoShortVarDeclaration -> p.varDefinitionList
        else -> listOf(def)
    }

    /** The values of [def]'s declaration (null for a `range` or a type switch variable, empty for `var x bool`). */
    private fun initializers(def: GoVarDefinition): List<GoExpression>? = when (val p = def.parent) {
        is GoVarSpec -> p.expressionList
        is GoShortVarDeclaration -> p.expressionList
        else -> null
    }

    /** The expression to negate for a reference [element] to [target]: the value assigned or keyed, the call, the read itself. */
    fun elementToInvert(target: PsiElement, element: PsiElement): PsiElement? {
        val ref = element as? GoReferenceExpression ?: return null
        if (target is GoFunctionOrMethodDeclaration) return GoParameterRemoval.callSiteOf(ref, target)?.call
        val parent = ref.parent
        if (parent is GoLeftHandExprList) {
            val assign = parent.parent as? GoAssignmentStatement ?: return null
            val targets = parent.expressionList
            val values = assign.expressionList
            return if (values.size == targets.size) values[targets.indexOf(ref)] else null
        }
        if (parent is GoKey) return (parent.parent as? GoElement)?.value?.expression
        return ref
    }

    /** The argument of each call of [param]'s function bound to it. */
    fun arguments(param: GoParamDefinition): List<PsiElement> {
        val owner = GoParameterRemoval.ownerOf(param) as? GoFunctionOrMethodDeclaration ?: return emptyList()
        val signature = owner.signature
        val slot = GoParameterRemoval.slot(signature, param)
        val arity = GoParameterRemoval.arity(signature)
        val variadic = GoParameterRemoval.isVariadic(signature)
        return GoParameterRemoval.references(owner, owner.useScope, stopAtValue = false).calls
            .mapNotNull { GoParameterRemoval.argumentsAt(it, slot, arity, variadic)?.singleOrNull() }
    }

    /** Replaces [expr] by its negation (or removes the `!` around it, or flips the literal it is compared with). */
    fun negate(expr: GoExpression) {
        var outer: PsiElement = expr
        while (outer.parent is GoParenthesesExpr) outer = outer.parent
        val parent = outer.parent
        if (parent is GoUnaryExpr && parent.not != null) return replace(parent, GoIfText.unparen(expr))
        if (parent is GoConditionalExpr && (parent.eql != null || parent.neq != null)) {
            val other = if (parent.left === outer) parent.right else parent.left
            if (other != null && literal(other) != null) return replaceText(other, if (literal(other) == true) "false" else "true", 7)
        }
        val u = GoIfText.unparen(expr)
        when {
            u is GoUnaryExpr && u.not != null && u.expression != null -> replace(expr, GoIfText.unparen(u.expression!!))
            literal(u) != null -> replaceText(expr, if (literal(u) == true) "false" else "true", 7)
            u is GoBinaryExpr && GoExpressionText.isBoolean(u) && u.right != null ->
                GoExpressionText.negated(u, false)?.let { replaceText(expr, it.text, it.prec) } ?: replaceText(expr, "!(${u.text})", 6)
            GoInlineSupport.precedence(u) >= 7 -> replaceText(expr, "!${u.text}", 6)
            else -> replaceText(expr, "!(${u.text})", 6)
        }
    }

    private fun literal(e: PsiElement): Boolean? {
        val u = (e as? GoExpression)?.let { GoIfText.unparen(it) } as? GoReferenceExpression ?: return null
        if (u.expression != null) return null
        return when (u.text) { "true" -> true; "false" -> false; else -> null }
    }

    private fun replace(place: PsiElement, by: GoExpression) = replaceText(place, by.text, GoInlineSupport.precedence(by))

    private fun replaceText(place: PsiElement, text: String, prec: Int) {
        val placed = if (place is GoExpression) GoInlineSupport.placed(place, text, prec) else text
        place.replace(expression(place.project, placed))
    }

    private fun expression(project: Project, text: String): GoExpression {
        val file = GoElementFactory.createFileFromText(project, "package p\n\nvar _ = $text\n")
        return PsiTreeUtil.findChildOfType(file, GoVarSpec::class.java)!!.expressionList.single()
    }

    /** Negates what [target] starts with: its initializer (`var x bool` gets ` = true`), or every `return` value of the function. */
    fun invertInitializer(target: PsiElement) {
        when (target) {
            is GoVarDefinition -> {
                val values = initializers(target) ?: return
                val defs = definitions(target)
                if (values.size == defs.size) return negate(values[defs.indexOf(target)])
                val spec = target.parent as? GoVarSpec ?: return
                if (values.isEmpty() && defs.size == 1) {
                    val documents = PsiDocumentManager.getInstance(target.project)
                    val document = documents.getDocument(spec.containingFile) ?: return
                    documents.doPostponedOperationsAndUnblockDocument(document)
                    document.insertString(spec.textRange.endOffset, " = true")
                    documents.commitDocument(document)
                }
            }
            is GoFunctionOrMethodDeclaration -> {
                val body = target.block ?: return
                val returns = PsiTreeUtil.findChildrenOfType(body, GoReturnStatement::class.java)
                    .filter { PsiTreeUtil.getParentOfType(it, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java) === target }
                for (r in returns) r.expressionList.singleOrNull()?.let(::negate)
            }
        }
    }

    // --- names ---

    private val ANTONYMS = listOf(
        "Enabled" to "Disabled", "Enable" to "Disable", "Visible" to "Hidden", "Valid" to "Invalid", "Open" to "Closed", "Active" to "Inactive",
        "Available" to "Unavailable", "Allowed" to "Forbidden", "Success" to "Failure", "On" to "Off", "Found" to "Missing", "Present" to "Absent",
        "Locked" to "Unlocked", "Connected" to "Disconnected", "Empty" to "NonEmpty", "Started" to "Stopped",
    )

    private val WORDS = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    /** `isEnabled` -> `isDisabled`, `ok` -> `notOk`, `hasItems` -> `lacksItems` (and back), `notDone` -> `done`, else a `not` prefix. */
    fun invertedName(name: String): String {
        if (name.isEmpty()) return name
        val exported = name[0].isUpperCase()
        fun cased(s: String) = if (exported) s.replaceFirstChar(Char::uppercaseChar) else s.replaceFirstChar(Char::lowercaseChar)
        val words = name.split(WORDS).filter { it.isNotEmpty() }
        val first = words.first().lowercase()
        if (first == "not" && words.size > 1) return cased(words.drop(1).joinToString(""))
        if (first == "has" && words.size > 1) return cased("lacks" + words.drop(1).joinToString(""))
        if (first == "lacks" && words.size > 1) return cased("has" + words.drop(1).joinToString(""))
        for (i in words.indices.reversed()) {
            val w = words[i].replaceFirstChar(Char::uppercaseChar)
            val swap = ANTONYMS.firstNotNullOfOrNull { (a, b) -> if (w == a) b else if (w == b) a else null } ?: continue
            val replaced = words.toMutableList().also { it[i] = swap }
            return cased(replaced.joinToString(""))
        }
        if (first == "is" && words.size > 1) return cased("isNot" + words.drop(1).joinToString(""))
        return cased("not" + name.replaceFirstChar(Char::uppercaseChar))
    }
}

/** Plugs Go into the platform's Invert Boolean: availability, the usages to negate (see [GoInvertBoolean]), the negation itself. */
class GoInvertBooleanDelegate : InvertBooleanDelegate() {

    override fun isVisibleOnElement(element: PsiElement): Boolean =
        element.language == GoLanguage && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)

    // Offered on any Go name, like GoLand: a declaration that is not boolean gets a hint instead of a greyed-out item.
    override fun isAvailableOnElement(element: PsiElement): Boolean = isVisibleOnElement(element)

    /**
     * The declaration to invert; null after a hint (not boolean) or after our own dialog ran the refactoring: the platform's dialog would
     * offer the old name, ours suggests the inverted one ([GoInvertBoolean.invertedName]). Tests get the declaration back.
     */
    override fun adjustElement(element: PsiElement, project: Project, editor: Editor?): PsiElement? {
        val target = GoInvertBoolean.targetOf(element)
        val problem = if (target == null) "The caret should be on the name of a boolean variable, parameter, field or function" else GoInvertBoolean.problem(target)
        if (problem != null) {
            CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n$problem", GoInvertBoolean.TITLE, null)
            return null
        }
        if (ApplicationManager.getApplication().isUnitTestMode) return target
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, target!!)) return null
        GoInvertBooleanDialog(target).show()
        return null
    }

    override fun collectRefElements(element: PsiElement, renameProcessor: RenameProcessor?, newName: String, elementsToInvert: MutableCollection<in PsiElement>) {
        for (ref in ReferencesSearch.search(element, element.useScope).findAll()) {
            if (!collectElementsToInvert(element, ref.element, elementsToInvert)) collectForeignElementsToInvert(element, ref.element, GoLanguage, elementsToInvert)
        }
        if (element is GoParamDefinition) elementsToInvert.addAll(GoInvertBoolean.arguments(element))
    }

    override fun getElementToInvert(namedElement: PsiElement, element: PsiElement): PsiElement? = GoInvertBoolean.elementToInvert(namedElement, element)

    override fun replaceWithNegatedExpression(expression: PsiElement) {
        (expression as? GoExpression)?.let(GoInvertBoolean::negate)
    }

    override fun invertElementInitializer(element: PsiElement) = GoInvertBoolean.invertInitializer(element)
}

/** The name of the inverted declaration, prefilled with the suggested inverted name; OK runs the platform's processor. */
class GoInvertBooleanDialog(private val element: GoNamedElement) : RefactoringDialog(element.project, true) {
    private val nameField = JBTextField(GoInvertBoolean.invertedName(element.name ?: ""), 30)

    init {
        title = GoInvertBoolean.TITLE
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder().addLabeledComponent("Invert '${element.name}' and rename it to:", nameField, true).panel

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doAction() {
        val name = nameField.text.trim()
        if (!GoNamesValidator.isValidIdentifier(name)) {
            CommonRefactoringUtil.showErrorMessage(GoInvertBoolean.TITLE, "'$name' is not a valid Go identifier", null, project)
            return
        }
        invokeRefactoring(InvertBooleanProcessor(element as PsiNamedElement, name))
    }
}
