package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.refactoring.changeSignature.ParameterInfo
import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectClassDescriptor
import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectDelegate
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.refactoring.util.FixableUsageInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.intellij.util.ui.FormBuilder
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.rename.GoNamesValidator
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent

/** What the Introduce Parameter Object dialog would answer, for tests: the struct name and the parameters to move (null: defaults). */
class GoParameterObjectOptions @TestOnly constructor(val structName: String? = null, val included: List<String>? = null)

/**
 * Introduce Parameter Object (Refactor | Extract/Introduce): on a function or method with at least two parameters, the chosen ones
 * (the receiver and a variadic parameter are never offered) move into `type NameParams struct { … }` declared above the function; the
 * function takes `p NameParams` in place of the first of them, their uses in the body become `p.Field`, and every call passes
 * `NameParams{Field: arg, …}`. Fields are exported when the function is. Refused for generic functions, a method implementing an
 * interface, a function used as a value, a call passing a multi-value call as its arguments and a method called from another package.
 */
class GoIntroduceParameterObjectHandler @JvmOverloads constructor(private val options: GoParameterObjectOptions? = null) : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, project)) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val decl = GoChangeSignatureHandler().findTargetMember(file, editor) as? GoFunctionOrMethodDeclaration
            ?: return error(project, editor, "The caret should be on the name or the parameters of a function or method")
        run(project, decl, editor)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val decl = elements.singleOrNull() as? GoFunctionOrMethodDeclaration ?: return
        run(project, decl, dataContext?.let { CommonDataKeys.EDITOR.getData(it) })
    }

    private fun run(project: Project, decl: GoFunctionOrMethodDeclaration, editor: Editor?) {
        if (!GoImplementations.isInProject(decl)) return error(project, editor, "${decl.name} is not in the project")
        if (decl.typeParameters != null) return error(project, editor, "Generic functions are not supported")
        val candidates = GoParameterObject.candidates(decl)
        if (candidates.size < 2) return error(project, editor, "The function should have at least two named parameters besides a variadic one")
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, decl)) return
        val found = search(project, decl) ?: return
        found.problem?.let { return error(project, editor, it) }
        val defaultName = GoParameterObject.defaultStructName(decl)
        val (name, included) = if (ApplicationManager.getApplication().isUnitTestMode) {
            (options?.structName ?: defaultName) to (options?.included?.let { names -> candidates.filter { it.name in names } } ?: candidates)
        } else {
            val dialog = GoIntroduceParameterObjectDialog(project, decl, candidates, defaultName)
            if (!dialog.showAndGet()) return
            dialog.structName to dialog.included
        }
        GoParameterObject.validate(decl, name, included)?.let { return error(project, editor, it) }
        GoParameterObject.perform(project, decl, name, included, found.calls)
    }

    private class Found(val calls: List<GoParameterRemoval.CallSite>, val problem: String?)

    /** The calls of [decl] and why they cannot all be changed, searched under a modal progress. */
    private fun search(project: Project, decl: GoFunctionOrMethodDeclaration): Found? {
        val task = ThrowableComputable<Found, RuntimeException> {
            ReadAction.compute<Found, RuntimeException> {
                if (decl is GoMethodDeclaration && GoSignatureHierarchy.applies(decl) && GoSignatureHierarchy.of(decl).members.size > 1) {
                    return@compute Found(emptyList(), "${GoChangeSignature.displayName(decl)} implements an interface method")
                }
                val refs = GoParameterRemoval.references(decl, decl.useScope, stopAtValue = true)
                if (refs.values.isNotEmpty()) return@compute Found(emptyList(), "${decl.name} is used as a value: such uses cannot be changed")
                Found(refs.calls, GoParameterObject.callProblem(decl, refs.calls))
            }
        }
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(task, "Looking for Calls of ${decl.name}", true, project)
    }

    private fun error(project: Project, editor: Editor?, message: String) =
        CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n$message", GoParameterObject.TITLE, null)
}

/** The edits of Introduce Parameter Object (see [GoIntroduceParameterObjectHandler]). */
internal object GoParameterObject {
    const val TITLE: String = "Introduce Parameter Object"

    /** The parameters that may move: named (not `_`), not variadic. */
    fun candidates(decl: GoFunctionOrMethodDeclaration): List<GoParamDefinition> =
        GoParameterRemoval.declarations(decl.signature).filter { !it.isVariadic }.flatMap { it.paramDefinitionList }.filter { it.name != null && it.name != "_" }

    /** `LoadParams` for `Load`, `loadParams` for `load`. */
    fun defaultStructName(decl: GoFunctionOrMethodDeclaration): String = (decl.name ?: "f") + "Params"

    fun fieldName(decl: GoFunctionOrMethodDeclaration, param: GoParamDefinition): String =
        param.name!!.let { if (decl.isPublic()) it.replaceFirstChar(Char::uppercaseChar) else it }

    /** Why a struct [name] with [included] cannot be introduced for [decl], or null. */
    fun validate(decl: GoFunctionOrMethodDeclaration, name: String, included: List<GoParamDefinition>): String? {
        if (!GoNamesValidator.isValidIdentifier(name)) return "'$name' is not a valid Go identifier"
        if (included.isEmpty()) return "Choose the parameters to move into the struct"
        val file = decl.containingFile as? GoFile ?: return null
        if (GoUniverse.isBuiltin(name) || GoScopes.resolveName(file.packageClause ?: file, name).isNotEmpty()) return "'$name' is already declared in package ${file.packageName}"
        val fields = included.map { fieldName(decl, it) }
        fields.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return "Two parameters give the field ${it.key}" }
        return null
    }

    /** Why the arguments of [calls] cannot be regrouped: a multi-value call as the arguments, a method called from another package. */
    fun callProblem(decl: GoFunctionOrMethodDeclaration, calls: List<GoParameterRemoval.CallSite>): String? {
        val signature = decl.signature
        val arity = GoParameterRemoval.arity(signature)
        val variadic = GoParameterRemoval.isVariadic(signature)
        val file = decl.containingFile as? GoFile ?: return null
        for (site in calls) {
            if (GoParameterRemoval.argumentsAt(site, 0, arity, variadic) == null) return "A call of ${decl.name} passes the results of another call as its arguments"
            val other = site.call.containingFile as? GoFile ?: continue
            if (!samePackage(file, other) && (decl is GoMethodDeclaration || qualifierOf(site) == null)) return "${decl.name} is called from package ${other.packageName}"
        }
        return null
    }

    private fun samePackage(a: GoFile, b: GoFile): Boolean =
        a.originalFile.virtualFile?.parent == b.originalFile.virtualFile?.parent && a.packageName == b.packageName

    /** `pkg.` of the call `pkg.F(…)`. */
    private fun qualifierOf(site: GoParameterRemoval.CallSite): String? =
        ((site.call.expression as? GoReferenceExpression)?.qualifier as? GoReferenceExpression)?.text?.let { "$it." }

    fun perform(project: Project, decl: GoFunctionOrMethodDeclaration, name: String, included: List<GoParamDefinition>, calls: List<GoParameterRemoval.CallSite>) {
        val file = decl.containingFile as GoFile
        val signature = decl.signature ?: return
        val arity = GoParameterRemoval.arity(signature)
        val variadic = GoParameterRemoval.isVariadic(signature)
        val slots = included.map { GoParameterRemoval.slot(signature, it) }
        val fields = included.map { fieldName(decl, it) }
        val types = included.map { (it.parent as io.github.golangsupport.lang.psi.GoParameterDeclaration).type?.text ?: "" }
        val body = decl.block
        val selectedNames = included.mapNotNull { it.name }.toSet()
        val localTaken = body?.let { GoExtraction.localTaken(it, decl) } ?: { _: String -> false }
        val param = GoExtraction.unique("p") { it !in selectedNames && localTaken(it) }
        val bodyEdits = included.withIndex().flatMap { (i, def) ->
            if (body == null) emptyList()
            else ReferencesSearch.search(def, LocalSearchScope(body)).findAll().map { it.rangeInElement.shiftRight(it.element.textRange.startOffset) to "$param.${fields[i]}" }
        }
        val pointers = SmartPointerManager.getInstance(project)
        val declPointer = pointers.createSmartPsiElementPointer(decl)
        val callPointers = calls.sortedByDescending { it.call.textRange.startOffset }.map { pointers.createSmartPsiElementPointer(it.call) to it.methodExpression }
        val files = (listOf<PsiFile>(file) + calls.map { it.call.containingFile }).distinct()
        val documents = PsiDocumentManager.getInstance(project)
        WriteCommandAction.writeCommandAction(project, *files.toTypedArray()).withName(TITLE).run<RuntimeException> {
            val document = documents.getDocument(file) ?: return@run
            documents.doPostponedOperationsAndUnblockDocument(document)
            for ((range, text) in bodyEdits.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
            documents.commitDocument(document)
            for ((pointer, methodExpression) in callPointers) {
                val call = pointer.element ?: continue
                val callFile = call.containingFile as? GoFile ?: continue
                val site = GoParameterRemoval.CallSite(call, methodExpression)
                val qualifier = if (samePackage(file, callFile)) "" else qualifierOf(site) ?: ""
                callEdit(site, slots, arity, variadic, "$qualifier$name", fields)?.let { (range, text) ->
                    val callDocument = documents.getDocument(callFile) ?: return@let
                    documents.doPostponedOperationsAndUnblockDocument(callDocument)
                    callDocument.replaceString(range.startOffset, range.endOffset, text)
                    documents.commitDocument(callDocument)
                }
            }
            val fresh = declPointer.element ?: return@run
            val parameters = fresh.signature?.parameters ?: return@run
            val old = GoChangeSignature.parametersOf(fresh.signature)
            val first = slots.min()
            val updated = old.withIndex().mapNotNull { (i, p) ->
                when {
                    i == first -> GoChangeParameter(param, name)
                    i in slots -> null
                    else -> p
                }
            }
            documents.doPostponedOperationsAndUnblockDocument(document)
            document.replaceString(parameters.textRange.startOffset, parameters.textRange.endOffset, GoChangeSignature.parametersText(updated))
            val at = fresh.docComment?.textRange?.startOffset ?: fresh.textRange.startOffset
            val struct = fields.indices.joinToString("", "type $name struct {\n", "}\n\n") { "\t${fields[it]} ${types[it]}\n" }
            document.insertString(at, struct)
            documents.commitDocument(document)
            PsiTreeUtil.findElementOfClassAtOffset(file, at, GoTypeDeclaration::class.java, false)?.let { CodeStyleManager.getInstance(project).reformat(it) }
        }
    }

    /** The argument list of [site] with the arguments of [slots] wrapped into `Struct{Field: arg, …}` at the first of them. */
    private fun callEdit(site: GoParameterRemoval.CallSite, slots: List<Int>, arity: Int, variadic: Boolean, struct: String, fields: List<String>): Pair<TextRange, String>? {
        val list = site.call.argumentList ?: return null
        val open = list.lparen.textRange.endOffset
        val close = list.rparen?.textRange?.startOffset ?: return null
        val all = site.call.arguments
        val parts = ArrayList<String>()
        if (site.methodExpression && all.isNotEmpty()) parts += all[0].text
        val first = slots.min()
        val spread = list.node.findChildByType(io.github.golangsupport.lang.psi.GoTypes.ELLIPSIS) != null
        for (i in 0 until arity) {
            val args = GoParameterRemoval.argumentsAt(site, i, arity, variadic) ?: return null
            when {
                i == first -> parts += slots.indices.joinToString(", ", "$struct{", "}") { k ->
                    "${fields[k]}: ${GoParameterRemoval.argumentsAt(site, slots[k], arity, variadic)?.singleOrNull()?.text ?: ""}"
                }
                i in slots -> {}
                args.isEmpty() -> {}
                else -> parts += args.joinToString(", ") { it.text }
            }
        }
        return TextRange(open, close) to parts.joinToString(", ") + if (spread) "..." else ""
    }
}

/** The struct name and the parameters to move. */
class GoIntroduceParameterObjectDialog(project: Project, decl: GoFunctionOrMethodDeclaration, private val candidates: List<GoParamDefinition>, defaultName: String) :
    DialogWrapper(project, true) {
    private val nameField = JBTextField(defaultName, 30)
    private val list = CheckBoxList<GoParamDefinition>()

    init {
        title = GoParameterObject.TITLE
        for (p in candidates) list.addItem(p, p.name + " " + ((p.parent as io.github.golangsupport.lang.psi.GoParameterDeclaration).type?.text ?: ""), true)
        setOKButtonText("Refactor")
        init()
    }

    val structName: String get() = nameField.text.trim()

    val included: List<GoParamDefinition> get() = candidates.filter { list.isItemSelected(it) }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Struct name:", nameField, true)
        .addLabeledComponent("Parameters to move:", JBScrollPane(list), true)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = when {
        !GoNamesValidator.isValidIdentifier(structName) -> ValidationInfo("Not a valid Go identifier", nameField)
        included.isEmpty() -> ValidationInfo("Choose at least one parameter", list)
        else -> null
    }
}

/**
 * Lets the platform's Introduce Parameter Object action (and Refactor This) offer the Go handler. The platform's own processor is
 * Java-shaped (change info, fixable usages); the Go refactoring is [GoIntroduceParameterObjectHandler], so the rest is never called.
 */
class GoIntroduceParameterObjectDelegate :
    IntroduceParameterObjectDelegate<PsiNamedElement, ParameterInfo, IntroduceParameterObjectClassDescriptor<PsiNamedElement, ParameterInfo>>() {

    override fun isEnabledOn(element: PsiElement): Boolean {
        if (element.language != GoLanguage || !GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)) return false
        val decl = (element as? GoFunctionOrMethodDeclaration) ?: GoChangeSignatureHandler().findTargetMember(element) as? GoFunctionOrMethodDeclaration
        return decl != null || element.containingFile is GoFile
    }

    override fun getHandler(element: PsiElement): RefactoringActionHandler = GoIntroduceParameterObjectHandler()

    override fun getAllMethodParameters(sourceMethod: PsiNamedElement): List<ParameterInfo> = emptyList()

    override fun createMergedParameterInfo(
        descriptor: IntroduceParameterObjectClassDescriptor<PsiNamedElement, ParameterInfo>, method: PsiNamedElement, oldMethodParameters: List<ParameterInfo>,
    ): ParameterInfo = throw UnsupportedOperationException()

    override fun createNewParameterInitializerAtCallSite(
        callExpression: PsiElement, descriptor: IntroduceParameterObjectClassDescriptor<*, *>, oldMethodParameters: List<ParameterInfo>, substitutor: Any?,
    ): PsiElement = throw UnsupportedOperationException()

    override fun createChangeSignatureInfo(method: PsiNamedElement, newParameterInfos: List<ParameterInfo>, delegate: Boolean): ChangeInfo =
        throw UnsupportedOperationException()

    override fun <M1 : PsiNamedElement, P1 : ParameterInfo> collectInternalUsages(
        usages: MutableCollection<in FixableUsageInfo>, overridingMethod: PsiNamedElement, classDescriptor: IntroduceParameterObjectClassDescriptor<M1, P1>,
        parameterInfo: P1, mergedParamName: String,
    ): ReadWriteAccessDetector.Access? = null

    override fun collectUsagesToGenerateMissedFieldAccessors(
        usages: MutableCollection<in FixableUsageInfo>, method: PsiNamedElement,
        descriptor: IntroduceParameterObjectClassDescriptor<PsiNamedElement, ParameterInfo>, accessors: Array<out ReadWriteAccessDetector.Access>,
    ) = Unit

    override fun collectAdditionalFixes(
        usages: MutableCollection<in FixableUsageInfo>, method: PsiNamedElement, descriptor: IntroduceParameterObjectClassDescriptor<PsiNamedElement, ParameterInfo>,
    ) = Unit

    override fun collectConflicts(
        conflicts: MultiMap<PsiElement, String>, infos: Array<out UsageInfo>, method: PsiNamedElement,
        descriptor: IntroduceParameterObjectClassDescriptor<PsiNamedElement, ParameterInfo>,
    ) = Unit
}
