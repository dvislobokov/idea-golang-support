package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.intentions.GoIntentionText
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoNamedType
import org.jetbrains.annotations.TestOnly
import io.github.golangsupport.lang.psi.GoStructType as GoStructTypePsi

/** What the Introduce Field popups would answer, for tests: occurrences and name as [GoIntroduceOptions], and where the field is set. */
class GoIntroduceFieldOptions @TestOnly constructor(val replaceAll: Boolean = false, val name: String? = null, val initializeHere: Boolean = true)

/**
 * Introduce Field (Ctrl+Alt+F): in a method whose receiver is a named struct type of the project, the selected expression becomes a
 * new unexported field of that struct (type from the semantic model, name suggested and unique among the type's fields and methods)
 * and the expression (or all its equivalents in the method) is replaced by `r.name`. A popup asks where the value comes from:
 * "Initialize in current method" puts `r.name = expr` before the statement evaluating the first replaced occurrence; "Leave
 * initialization to the caller" only declares the field. The field goes after the line of the last one (its comment included);
 * refused when the project builds the struct with a positional literal (`S{1, 2}`), which the new field would break.
 */
class GoIntroduceFieldHandler @JvmOverloads constructor(private val fieldOptions: GoIntroduceFieldOptions? = null) :
    GoIntroduceHandlerBase(fieldOptions?.let { GoIntroduceOptions(it.replaceAll, it.name) }) {
    override val title: String = TITLE

    override fun problem(expr: GoExpression): String? {
        GoExtraction.rejectReason(expr)?.let { return it }
        val method = methodOf(expr) ?: return "The expression should be inside a method with a struct receiver"
        val receiver = method.receiver
        val receiverName = receiver?.identifier?.text
        if (receiverName == null || receiverName == "_") return "The receiver of the method has no name"
        val spec = GoImplementations.receiverTypeSpec(method) ?: return "Cannot find the receiver type"
        if (spec.type !is GoStructTypePsi) return "The receiver type is not a struct"
        val type = GoSemanticService.getInstance(expr.project).typeOf(expr)
        if (GoExtraction.isValueless(type)) return "The expression has no value"
        if (GoIntroduceSupport.isTuple(type)) return "The expression has several values"
        return null
    }

    override fun introduce(project: Project, editor: Editor, file: GoFile, expr: GoExpression) {
        val method = methodOf(expr) ?: return
        val spec = GoImplementations.receiverTypeSpec(method) ?: return
        if (!GoImplementations.isInProject(spec)) return error(project, editor, "The receiver type is not in the project")
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, spec)) return
        // a new field breaks every `S{1, 2}` of the project: refuse rather than leave code that does not compile
        val positional = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String?, RuntimeException> { ReadAction.compute<String?, RuntimeException> { positionalLiteral(spec) } },
                "Finding Literals of ${spec.name}", true, project,
            )
        } catch (_: ProcessCanceledException) {
            return
        }
        if (positional != null) return error(project, editor, "${spec.name} is built with a positional literal at $positional: a new field would break it")
        val type = GoIntroduceSupport.declaredType(expr)
        if (GoIntroduceSupport.isLocalType(type)) return error(project, editor, "The type of the expression is declared inside the function")
        val structFile = spec.containingFile as? GoFile ?: return
        val source = GoSourceText(structFile)
        val typeText = source.type(type)
        val receiver = method.receiver?.identifier?.text ?: return
        val all = GoExtraction.occurrences(expr, method.block ?: return) { problem(it) == null }
        chooseOccurrences(editor, expr, all) { chosen ->
            val names = fieldNames(expr, spec)
            val name = fieldOptions?.name ?: names.first()
            chooseInitialization(editor) { here ->
                val anchor = if (!here) null else if (chosen.size > 1) GoExtraction.commonAnchor(chosen) else GoExtraction.anchorOf(expr)
                if (here && anchor == null) return@chooseInitialization error(project, editor, "A statement cannot be inserted where the expression is evaluated")
                perform(project, editor, file, spec, chosen, anchor, "$receiver.$name", name, typeText, expr.text, source.imports, names)
            }
        }
    }

    /** Asks where the field gets its value; in tests [GoIntroduceFieldOptions.initializeHere] answers. */
    private fun chooseInitialization(editor: Editor, then: (Boolean) -> Unit) {
        if (ApplicationManager.getApplication().isUnitTestMode) return then(fieldOptions?.initializeHere ?: true)
        JBPopupFactory.getInstance().createPopupChooserBuilder(listOf(HERE, CALLER))
            .setTitle("Initialize Field")
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .setItemChosenCallback { then(it == HERE) }
            .createPopup().showInBestPositionFor(editor)
    }

    private fun perform(
        project: Project, editor: Editor, file: GoFile, spec: GoTypeSpec, chosen: List<GoExpression>, anchor: GoStatement?, access: String,
        name: String, type: String, value: String, imports: Collection<String>, names: List<String>,
    ) {
        val structFile = spec.containingFile
        val specPointer = SmartPointerManager.createPointer(spec)
        WriteCommandAction.writeCommandAction(project, file, structFile).withName(TITLE).run<RuntimeException> {
            val documents = PsiDocumentManager.getInstance(project)
            val document = editor.document
            documents.doPostponedOperationsAndUnblockDocument(document)
            val anchorMarker = anchor?.let { document.createRangeMarker(it.textRange) }
            for (r in chosen.map { it.textRange }.sortedByDescending { it.startOffset }) document.replaceString(r.startOffset, r.endOffset, access)
            if (anchorMarker != null) {
                val offset = anchorMarker.startOffset
                document.insertString(offset, "$access = $value\n" + GoIntentionText.indentAt(document.charsSequence, offset))
                anchorMarker.dispose()
            }
            documents.commitDocument(document)
            specPointer.element?.let { addField(it, name, type) }
            val structDocument = documents.getDocument(structFile)
            if (structDocument != null && structFile is GoFile) for (path in imports) {
                GoImportInserter.addImport(structFile, structDocument, path)
                documents.commitDocument(structDocument)
            }
        }
        val field = specPointer.element?.let { s -> PsiTreeUtil.findChildrenOfType(s, GoFieldDefinition::class.java).firstOrNull { it.name == name } }
        if (structFile == file) renameInPlace(editor, field, names, member = true)
    }

    /** Appends `name type` to the struct of [spec] and lets the formatter align the fields. */
    private fun addField(spec: GoTypeSpec, name: String, type: String) {
        val struct = spec.type as? GoStructTypePsi ?: return
        val file: PsiFile = spec.containingFile
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return
        documents.doPostponedOperationsAndUnblockDocument(document)
        val last = struct.fieldDeclarationList.lastOrNull()
        val lbrace = struct.lbrace ?: return
        val rbrace = struct.rbrace ?: return
        val baseIndent = GoIntentionText.indentAt(document.charsSequence, spec.textRange.startOffset)
        if (last != null) {
            val text = document.charsSequence
            val indent = GoIntentionText.indentAt(text, last.textRange.startOffset)
            // after the whole line of the last field, its trailing comment included; a one-line struct has none
            val lineEnd = text.indexOf('\n', last.textRange.endOffset).takeIf { it in 0 until rbrace.textRange.startOffset } ?: last.textRange.endOffset
            document.insertString(lineEnd, "\n$indent$name $type")
        } else {
            document.replaceString(lbrace.textRange.endOffset, rbrace.textRange.startOffset, "\n$baseIndent\t$name $type\n$baseIndent")
        }
        documents.commitDocument(document)
        val fresh = PsiTreeUtil.findElementOfClassAtOffset(file, spec.textRange.startOffset, GoTypeSpec::class.java, false)?.type ?: return
        CodeStyleManager.getInstance(file.project).reformat(fresh)
    }

    /** Names for the field: the variable names of the expression, unique among the fields and methods of the receiver type. */
    private fun fieldNames(expr: GoExpression, spec: GoTypeSpec): List<String> {
        val service = GoSemanticService.getInstance(expr.project)
        val named = service.declarationType(spec) as? GoNamedType
        val taken = { n: String -> GoUniverse.isBuiltin(n) || (named != null && service.lookupFieldOrMethod(named, n) != null) || n == spec.name }
        return GoExtraction.suggestNames(expr, GoIntroduceSupport.declaredType(expr)).map { GoExtraction.unique(it, taken) }.distinct()
    }

    companion object {
        const val TITLE: String = "Introduce Field"
        const val HERE: String = "Initialize in current method"
        const val CALLER: String = "Leave initialization to the caller"

        /**
         * "file.go:line" of the first positional (unkeyed, non-empty) composite literal of the project building [spec]: `S{1, 2}`,
         * `&S{…}`, or an element of `[]S{{1, 2}}` / `map[K]S{k: {1, 2}}` with the type elided. Read action.
         */
        fun positionalLiteral(spec: GoTypeSpec): String? {
            val found = ArrayList<PsiElement>()
            for (ref in ReferencesSearch.search(spec, GlobalSearchScope.projectScope(spec.project)).findAll()) {
                ProgressManager.checkCanceled()
                val element = ref.element
                val literal = PsiTreeUtil.getParentOfType(element, GoCompositeLit::class.java) ?: continue
                val literalType = literal.typeReferenceExpression ?: literal.typeList.firstOrNull() ?: continue
                if (!PsiTreeUtil.isAncestor(literalType, element, false)) continue
                val value = literal.literalValue ?: continue
                if (literalType is GoTypeReferenceExpression || (literalType as? GoType)?.typeReferenceExpression != null) {
                    if (isPositional(value)) found += value
                } else {
                    // `[]S{…}`, `[N]S{…}`, `map[K]S{…}`: the elements of type S are written without it
                    for (e in PsiTreeUtil.getChildrenOfTypeAsList(value, GoElement::class.java)) {
                        e.value?.literalValue?.takeIf(::isPositional)?.let { found += it }
                    }
                }
            }
            val first = found.minWithOrNull(compareBy({ it.containingFile.name }, { it.textRange.startOffset })) ?: return null
            val line = first.containingFile.viewProvider.contents.subSequence(0, first.textRange.startOffset).count { it == '\n' }
            return "${first.containingFile.name}:${line + 1}"
        }

        private fun isPositional(value: GoLiteralValue): Boolean = PsiTreeUtil.getChildrenOfTypeAsList(value, GoElement::class.java).firstOrNull()?.let { it.key == null } == true

        /** The method declaration whose body holds [expr] (through function literals). */
        fun methodOf(expr: GoExpression): GoMethodDeclaration? = GoIntroduceParameterHandler.declarationOf(expr) as? GoMethodDeclaration
    }
}
