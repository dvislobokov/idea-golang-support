package io.github.golangsupport.ide.refactoring

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.copy.CopyHandlerDelegateBase
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.intentions.GoIntentionText
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.rename.GoNamesValidator
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoNamedType
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent

/** What the Copy / Clone dialog would answer, for tests: the new name and the target file (a file of the same package; null: the same file). */
class GoCopyOptions @TestOnly constructor(val name: String, val targetFileName: String? = null)

/**
 * Copy (F5) and Clone of a top-level Go declaration: a function, method, type, or a variable or constant declared alone in its spec.
 * The copy gets a new name and goes after the original (Clone, or Copy into the same file) or at the end of another file of the package;
 * the declaration's references to itself (recursion, a method calling itself, a type naming itself) and the leading name of its doc
 * comment follow the new name, everything else is left as it is; the target file gets the imports the copy needs. Files and
 * directories are still copied by the platform: only the name of a declaration at the caret or a declaration in the structure is ours.
 */
class GoCopyDeclarationHandler : CopyHandlerDelegateBase() {

    override fun canCopy(elements: Array<out PsiElement>, fromUpdate: Boolean): Boolean = elements.size == 1 && GoCopyDeclaration.declarationOf(elements[0]) != null

    override fun getActionName(elements: Array<out PsiElement>): String = "Copy Declaration..."

    override fun doCopy(elements: Array<out PsiElement>, defaultTargetDirectory: PsiDirectory?) {
        GoCopyDeclaration.declarationOf(elements.singleOrNull() ?: return)?.let { ask(it, clone = false) }
    }

    override fun doClone(element: PsiElement) {
        GoCopyDeclaration.declarationOf(element)?.let { ask(it, clone = true) }
    }

    private fun ask(decl: GoNamedElement, clone: Boolean) {
        val project = decl.project
        val source = decl.containingFile as? GoFile ?: return
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, decl)) return
        val (name, target) = if (ApplicationManager.getApplication().isUnitTestMode) {
            val answer = testOptions ?: return
            answer.name to (answer.targetFileName?.let { n -> GoCopyDeclaration.packageFiles(source).firstOrNull { it.name == n } } ?: source)
        } else {
            val dialog = GoCopyDeclarationDialog(decl, clone)
            if (!dialog.showAndGet()) return
            dialog.newName to dialog.target
        }
        GoCopyDeclaration.problem(decl, name, target)?.let {
            CommonRefactoringUtil.showErrorMessage(GoCopyDeclaration.TITLE, it, null, project)
            return
        }
        val offset = GoCopyDeclaration.copy(decl, name, target) ?: return
        if (!ApplicationManager.getApplication().isUnitTestMode) target.virtualFile?.let { PsiNavigationSupport.getInstance().createNavigatable(project, it, offset).navigate(true) }
    }

    companion object {
        /** The dialog's answer in tests. */
        @TestOnly
        @JvmStatic
        var testOptions: GoCopyOptions? = null
    }
}

internal object GoCopyDeclaration {
    const val TITLE: String = "Copy Declaration"

    /** The top-level declaration [element] is or names (the identifier of its declaration), if it can be copied. */
    fun declarationOf(element: PsiElement): GoNamedElement? {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)) return null
        val decl = when {
            element is GoNamedElement -> element
            element.parent is GoNamedElement && (element.parent as GoNamedElement).nameIdentifier === element -> element.parent as GoNamedElement
            else -> return null
        }
        val ok = when (decl) {
            is GoFunctionOrMethodDeclaration -> decl.parent is GoFile
            is GoTypeSpec -> decl.parent is GoTypeDeclaration && decl.parent.parent is GoFile
            is GoVarDefinition -> (decl.parent as? GoVarSpec)?.let { it.varDefinitionList.size == 1 && it.parent is GoVarDeclaration && it.parent.parent is GoFile } == true
            is GoConstDefinition -> (decl.parent as? GoConstSpec)?.let { it.constDefinitionList.size == 1 && it.parent is GoConstDeclaration && it.parent.parent is GoFile } == true
            else -> false
        }
        return decl.takeIf { ok && it.containingFile is GoFile && GoImplementations.isInProject(it) }
    }

    /** The Go files of [file]'s package (same directory and package clause), [file] first. */
    fun packageFiles(file: GoFile): List<GoFile> {
        val others = file.containingDirectory?.files.orEmpty().filterIsInstance<GoFile>().filter { it != file && it.packageName == file.packageName }.sortedBy { it.name }
        return listOf(file) + others
    }

    fun defaultName(decl: GoNamedElement): String = (decl.name ?: "x") + "Copy"

    /** Why [decl] cannot be copied as [name] into [target], or null. */
    fun problem(decl: GoNamedElement, name: String, target: GoFile): String? {
        if (!GoNamesValidator.isValidIdentifier(name)) return "'$name' is not a valid Go identifier"
        if (decl is GoConstDefinition) {
            val spec = decl.parent as GoConstSpec
            if (spec.expressionList.isEmpty()) return "The constant takes its value from the previous one of its group"
            if (PsiTreeUtil.findChildrenOfType(spec, GoReferenceExpression::class.java).any { it.expression == null && it.text == "iota" }) return "The constant uses iota"
        }
        if (decl is GoMethodDeclaration) {
            val spec = GoImplementations.receiverTypeSpec(decl) ?: return null
            val service = GoSemanticService.getInstance(decl.project)
            val named = service.declarationType(spec) as? GoNamedType ?: return null
            return if (service.lookupFieldOrMethod(named, name) != null) "${spec.name} already has a field or method $name" else null
        }
        val clash = GoScopes.resolveName(target.packageClause ?: target, name).any { (it.element.containingFile as? GoFile)?.packageName != "builtin" }
        return if (clash) "'$name' is already declared in package ${target.packageName}" else null
    }

    /** Inserts the copy of [decl] named [name] into [target]; the offset of the new name there. */
    fun copy(decl: GoNamedElement, name: String, target: GoFile): Int? {
        val project = decl.project
        val source = decl.containingFile as? GoFile ?: return null
        val (text, nameOffset) = copiedText(decl, name) ?: return null
        val top = topOf(decl)
        val documents = PsiDocumentManager.getInstance(project)
        var result: Int? = null
        WriteCommandAction.writeCommandAction(project, target).withName(TITLE).run<RuntimeException> {
            val document = documents.getDocument(target) ?: return@run
            documents.doPostponedOperationsAndUnblockDocument(document)
            val (offset, prefix, suffix) = if (target == source) Triple(top.textRange.endOffset, "\n\n", "") else {
                val chars = document.charsSequence
                Triple(chars.length, if (chars.endsWith("\n\n")) "" else if (chars.endsWith("\n")) "\n" else "\n\n", "\n")
            }
            document.insertString(offset, prefix + text + suffix)
            documents.commitDocument(document)
            result = offset + prefix.length + nameOffset
            if (target != source) GoMissingImports.add(target, text)
        }
        return result
    }

    /** The top-level element holding [decl]: itself for a function, the `type` / `var` / `const` declaration for a spec. */
    private fun topOf(decl: GoNamedElement): PsiElement = when (decl) {
        is GoFunctionOrMethodDeclaration -> decl
        is GoTypeSpec -> decl.parent
        else -> decl.parent.parent
    }

    /** The source of the copy and the offset of its name in it: the doc comment, the declaration, self references renamed. */
    private fun copiedText(decl: GoNamedElement, name: String): Pair<String, Int>? {
        val identifier = decl.nameIdentifier ?: return null
        val oldName = decl.name ?: return null
        val (body, keyword) = when (decl) {
            is GoFunctionOrMethodDeclaration -> decl to ""
            is GoTypeSpec -> decl to "type "
            is GoVarDefinition -> decl.parent to "var "
            else -> decl.parent to "const "
        }
        val doc = decl.docComment
        val start = body.textRange.startOffset
        val idStart = identifier.textRange.startOffset - start
        val edits = ArrayList<Pair<TextRange, String>>()
        edits += TextRange.from(idStart, identifier.textLength) to name
        for (ref in ReferencesSearch.search(decl, LocalSearchScope(body)).findAll()) {
            val r = ref.rangeInElement.shiftRight(ref.element.textRange.startOffset)
            if (r != identifier.textRange) edits += r.shiftLeft(start) to name
        }
        // The doc comment's leading name: the PSI binds a function's comment into its node, a spec's may stand before `type` / `var`.
        var docText = ""
        val renamesDoc = doc != null && doc.text.startsWith("// $oldName") &&
            doc.text.getOrNull(3 + oldName.length)?.let { Character.isLetterOrDigit(it) || it == '_' } != true
        if (doc != null && body.textRange.contains(doc.textRange)) {
            if (renamesDoc) edits += TextRange.from(doc.textRange.startOffset + 3 - start, oldName.length) to name
        } else if (doc != null) {
            docText = (if (renamesDoc) "// $name" + doc.text.substring(3 + oldName.length) else doc.text) + "\n"
        }
        val unique = edits.distinctBy { it.first }
        val sb = StringBuilder(body.text)
        for ((r, t) in unique.sortedByDescending { it.first.startOffset }) sb.replace(r.startOffset, r.endOffset, t)
        val shift = unique.filter { it.first.endOffset <= idStart }.sumOf { it.second.length - it.first.length }
        // A spec of a group `type ( … )` is indented: the copy stands alone at the top level.
        val indent = GoIntentionText.indentAt(body.containingFile.viewProvider.contents, start)
        val code = if (indent.isEmpty()) sb.toString() else sb.lines().joinToString("\n") { it.removePrefix(indent) }
        return (docText + keyword + code) to docText.length + keyword.length + idStart + shift
    }
}

/** The new name and, for Copy, the file of the package to put the copy in. */
class GoCopyDeclarationDialog(decl: GoNamedElement, private val clone: Boolean) : DialogWrapper(decl.project, true) {
    private val nameField = JBTextField(GoCopyDeclaration.defaultName(decl), 30)
    private val files = ComboBox((decl.containingFile as GoFile).let { GoCopyDeclaration.packageFiles(it) }.toTypedArray())

    init {
        title = if (clone) "Clone ${decl.name}" else "Copy ${decl.name}"
        files.renderer = SimpleListCellRenderer.create("") { it.name }
        init()
    }

    val newName: String get() = nameField.text.trim()

    val target: GoFile get() = files.selectedItem as GoFile

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder().addLabeledComponent("New name:", nameField, true)
        if (!clone) form.addLabeledComponent("To file:", files, true)
        return form.panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = if (GoNamesValidator.isValidIdentifier(newName)) null else ValidationInfo("Not a valid Go identifier", nameField)
}
