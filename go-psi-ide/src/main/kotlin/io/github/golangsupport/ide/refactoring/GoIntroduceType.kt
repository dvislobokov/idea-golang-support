package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.PsiEquivalenceUtil
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoArrayOrSliceType
import io.github.golangsupport.lang.psi.GoChannelType
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionType
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMapType
import io.github.golangsupport.lang.psi.GoParType
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoPointerType
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSpecType
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoTypeList
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse

/** The pure part of Introduce Type: which type expressions qualify, their occurrences, names and the text of the new declaration. */
object GoIntroduceType {

    /** The type expressions around [offset] (the caret, or just before it) that can be introduced, innermost first. */
    fun targetsAt(file: GoFile, offset: Int): List<GoType> {
        val result = LinkedHashSet<GoType>()
        for (at in intArrayOf(offset, offset - 1)) {
            var e: PsiElement? = file.findElementAt(at.coerceAtLeast(0))
            while (e != null && e !is GoFile) {
                if (e is GoType && problem(e) == null) result += e
                // the caret in the value of `struct{…}{…}` means its type
                if (e is GoCompositeLit) PsiTreeUtil.getChildOfType(e, GoType::class.java)?.takeIf { problem(it) == null }?.let { result += it }
                e = e.parent
            }
            if (result.isNotEmpty()) break
        }
        return result.toList()
    }

    /** The type expression exactly covered by the selection [start, end) (blanks around it ignored). */
    fun selected(file: GoFile, start: Int, end: Int): GoType? {
        val text = file.viewProvider.contents
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        var element: PsiElement? = file.findElementAt(s)
        while (element != null && element !is GoFile) {
            if (element.textRange.startOffset != s) return null
            if (element is GoType && element.textRange.endOffset == e) return outermost(element)
            if (element.textRange.endOffset > e) return null
            element = element.parent
        }
        return null
    }

    /** A `type X struct{…}` gives the struct, not its spec wrapper: the outermost type with the same range. */
    private fun outermost(type: GoType): GoType {
        var t = type
        while (true) {
            val parent = t.parent as? GoType ?: return t
            if (parent.textRange != t.textRange) return t
            t = parent
        }
    }

    /** Why [type] cannot become a named type, or null. */
    fun problem(type: GoType): String? {
        if (type is GoSpecType || type.parent is GoSpecType || type.parent is GoTypeSpec) return "The type is already named"
        if (type is GoTypeList || type is GoParType) return "Select a type literal"
        if (type.typeReferenceExpression != null) return "The type is already named"
        val service = GoSemanticService.getInstance(type.project)
        for (ref in PsiTreeUtil.findChildrenOfType(type, GoTypeReferenceExpression::class.java)) {
            val target = service.resolve(ref) ?: continue
            if (target is GoTypeParamDefinition) return "The type uses the type parameter ${ref.text}"
            if (GoPsiUtil.functionOwner(target) != null) return "The type uses the local type ${ref.text}"
        }
        return null
    }

    /** The type expressions of [file] equivalent to [type] that can be replaced, [type] included, in order. */
    fun occurrences(type: GoType, file: GoFile): List<GoType> {
        val result = ArrayList<GoType>()
        PsiTreeUtil.processElements(file) { e ->
            if (e === type || (e is GoType && e.javaClass == type.javaClass && problem(e) == null && PsiEquivalenceUtil.areElementsEquivalent(e, type))) result += e as GoType
            true
        }
        return result.filter { e -> e === type || result.none { o -> o !== e && o.textRange.contains(e.textRange) } }.sortedBy { it.textRange.startOffset }
    }

    /** The top-level declaration the new type goes above: the first one holding an occurrence. */
    fun anchor(file: GoFile, occurrences: List<GoType>): PsiElement? =
        occurrences.map { PsiTreeUtil.findPrevParent(file, it) }.minByOrNull { it.textRange.startOffset }

    /** Where `type Name …` is inserted above [anchor]: before the comment lines directly over it. */
    fun insertionOffset(text: CharSequence, anchor: PsiElement): Int {
        var start = text.lastIndexOf('\n', anchor.textRange.startOffset - 1) + 1
        while (start > 0) {
            val previous = text.lastIndexOf('\n', start - 2) + 1
            if (!text.subSequence(previous, start).trimStart().startsWith("//")) break
            start = previous
        }
        return start
    }

    /** [typeText] (as written at [offset] of [text]) moved to column 0: the indent of its first line is taken off the lines after it. */
    fun unindented(text: CharSequence, offset: Int, typeText: String): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val indent = text.subSequence(lineStart, offset).takeWhile { it == ' ' || it == '\t' }.toString()
        if (indent.isEmpty()) return typeText
        return typeText.lines().mapIndexed { i, line -> if (i > 0 && line.startsWith(indent)) line.substring(indent.length) else line }.joinToString("\n")
    }

    /** Names for [type], in UpperCamel case: from the field, parameter or variable it types, else from what it is. */
    fun suggestNames(type: GoType): List<String> {
        val names = ArrayList<String>()
        contextName(type)?.let { names += upper(it) }
        when (type) {
            is GoStructType -> {
                val fields = type.fieldDeclarationList.flatMap { d -> d.fieldDefinitionList.mapNotNull { it.name } }
                if (fields.size in 1..2) names += fields.joinToString("") { upper(it) }
                names += "Config"
            }
            is GoFunctionType -> names += listOf("Handler", "Func")
            is GoMapType -> names += (type.typeList.getOrNull(1)?.let(::elementName)?.let { "${it}Map" } ?: "Map")
            is GoArrayOrSliceType -> names += (type.type?.let(::elementName)?.let { "${it}List" } ?: "List")
            is GoChannelType -> names += (type.type?.let(::elementName)?.let { "${it}Chan" } ?: "Chan")
            is GoPointerType -> type.type?.let(::elementName)?.let { names += "${it}Ref" }
            is GoInterfaceType -> names += "Interface"
        }
        names += "T"
        return names.filter { it.isNotEmpty() && it[0].isLetter() }.distinct()
    }

    private fun elementName(type: GoType): String? =
        type.typeReferenceExpression?.identifier?.text?.let(::upper) ?: (type as? GoStructType)?.let { "Item" }

    /** The name the type is declared for: `cfg struct{…}`, `cfg := struct{…}{…}`, `Opts: struct{…}{…}`. */
    private fun contextName(type: GoType): String? {
        when (val parent = type.parent) {
            is GoFieldDeclaration -> return parent.fieldDefinitionList.singleOrNull()?.name
            is GoParameterDeclaration -> return parent.paramDefinitionList.singleOrNull()?.name
            is GoVarSpec -> return parent.varDefinitionList.singleOrNull()?.name
            is GoCompositeLit -> {
                val literal: PsiElement = parent
                when (val holder = literal.parent) {
                    is GoShortVarDeclaration -> return holder.varDefinitionList.getOrNull(holder.expressionList.indexOfFirst { it === literal })?.name
                    is GoVarSpec -> return holder.varDefinitionList.getOrNull(holder.expressionList.indexOfFirst { it === literal })?.name
                }
                val element = PsiTreeUtil.getParentOfType(literal, GoElement::class.java)
                if (element != null && element.value?.let { PsiTreeUtil.isAncestor(it, literal, false) } == true) {
                    return element.key?.expression?.text?.takeIf { k -> k.all { it.isLetterOrDigit() || it == '_' } }
                }
            }
        }
        return null
    }

    private fun upper(name: String): String = name.replaceFirstChar { it.uppercaseChar() }

    /** [suggestNames] made unique: no name of the package, no builtin, nothing visible at an occurrence. */
    fun names(type: GoType, occurrences: List<GoType>, file: GoFile): List<String> {
        val packageScope: PsiElement = file.packageClause ?: file
        val taken = { n: String -> GoUniverse.isBuiltin(n) || GoScopes.resolveName(packageScope, n).isNotEmpty() || occurrences.any { GoScopes.resolveName(it, n).isNotEmpty() } }
        return suggestNames(type).map { GoExtraction.unique(it, taken) }.distinct()
    }
}

/**
 * Refactor | Introduce Type: a type literal (`struct{…}`, `func(…)`, `map[K]V`, `[]T`, the type of a composite literal) becomes
 * `type Name …` above the top-level declaration holding it, and it (or every equivalent type expression of the file) is replaced by the
 * name, typed over in place afterwards. Not offered on a named type, a type spec's own type, or a type naming a type parameter or a
 * local type (the declaration would not see them at package level).
 */
class GoIntroduceTypeHandler @JvmOverloads constructor(private val options: GoIntroduceOptions? = null) : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, project)) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val selection = editor.selectionModel
        if (selection.hasSelection()) {
            val type = GoIntroduceType.selected(file, selection.selectionStart, selection.selectionEnd)
                ?: return error(project, editor, "Selected block should represent a type")
            GoIntroduceType.problem(type)?.let { return error(project, editor, it) }
            return introduce(project, editor, file, type)
        }
        val candidates = GoIntroduceType.targetsAt(file, editor.caretModel.offset)
        if (candidates.isEmpty()) return error(project, editor, "The caret should be on a type literal")
        if (candidates.size == 1 || ApplicationManager.getApplication().isUnitTestMode) return introduce(project, editor, file, candidates.first())
        IntroduceTargetChooser.showChooser(editor, candidates, object : Pass<GoType>() {
            override fun pass(type: GoType) = introduce(project, editor, file, type)
        }, { t: GoType -> t.text.replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57) + "..." else it } }, "Types")
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit

    private fun error(project: Project, editor: Editor, message: String) =
        CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n$message", TITLE, null)

    private fun introduce(project: Project, editor: Editor, file: GoFile, type: GoType) {
        val all = GoIntroduceType.occurrences(type, file)
        choose(editor, type, all) { chosen ->
            val names = GoIntroduceType.names(type, chosen, file)
            val name = options?.name ?: names.first()
            val anchor = GoIntroduceType.anchor(file, chosen) ?: return@choose
            val text = file.viewProvider.contents
            val offset = GoIntroduceType.insertionOffset(text, anchor)
            val declaration = "type $name ${GoIntroduceType.unindented(text, type.textRange.startOffset, type.text)}\n\n"
            val ranges = chosen.map { it.textRange }.sortedByDescending { it.startOffset }
            WriteCommandAction.writeCommandAction(project, file).withName(TITLE).run<RuntimeException> {
                val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run
                for (r in ranges) document.replaceString(r.startOffset, r.endOffset, name)
                document.insertString(offset, declaration)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            val spec = PsiTreeUtil.findElementOfClassAtOffset(file, offset + "type ".length, GoTypeSpec::class.java, false) ?: return@choose
            if (ApplicationManager.getApplication().isUnitTestMode || !editor.settings.isVariableInplaceRenameEnabled) return@choose
            editor.caretModel.moveToOffset(spec.textOffset)
            MemberInplaceRenamer(spec, null, editor).performInplaceRename(LinkedHashSet(names))
        }
    }

    /** This one or all [occurrences] (more than one; all by default), then [then]. */
    private fun choose(editor: Editor, type: GoType, occurrences: List<GoType>, then: (List<GoType>) -> Unit) {
        if (occurrences.size <= 1) return then(listOf(type))
        if (ApplicationManager.getApplication().isUnitTestMode) return then(if (options?.replaceAll != false) occurrences else listOf(type))
        OccurrencesChooser.simpleChooser<GoType>(editor).showChooser(type, occurrences, object : Pass<OccurrencesChooser.ReplaceChoice>() {
            override fun pass(choice: OccurrencesChooser.ReplaceChoice) = then(if (choice == OccurrencesChooser.ReplaceChoice.NO) listOf(type) else occurrences)
        })
    }

    companion object {
        const val TITLE: String = "Introduce Type"
    }
}

/** Refactor | Extract/Introduce | Introduce Type… in a Go editor. */
class GoIntroduceTypeAction : BaseRefactoringAction() {
    override fun isAvailableInEditorOnly(): Boolean = true
    override fun isEnabledOnElements(elements: Array<out PsiElement>): Boolean = false
    override fun isAvailableForLanguage(language: Language): Boolean = language.isKindOf(GoLanguage)
    override fun isAvailableOnElementInEditorAndFile(element: PsiElement, editor: Editor, file: PsiFile, context: DataContext): Boolean =
        file is GoFile && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, file.project)
    override fun getHandler(dataContext: DataContext): RefactoringActionHandler = GoIntroduceTypeHandler()
}
