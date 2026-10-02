package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeRenderer

/** Text edits of one intention, computed from the PSI under the read action, applied to the document; [imports] are added afterwards. */
class GoEditPlan(val edits: List<Edit>, val imports: Collection<String> = emptyList(), val text: String? = null) {
    class Edit(val start: Int, val end: Int, val text: String)
}

/**
 * Base of the intentions that rewrite code by its types (MIGRATION.md step 9): available only while [GoIdeFeature.CODE_ACTIONS]
 * is on (the host may give the group to gopls), compute a [GoEditPlan] from the PSI at the caret and apply it as document edits
 * (like the quick fixes of `ide.inspections`), then add the imports the new text needs through [GoImportInserter].
 */
abstract class GoCodeActionIntention : IntentionAction {
    @Volatile private var lastText: String? = null

    /** The name in the list; a plan may name itself differently ([GoEditPlan.text]). */
    protected abstract val defaultText: String

    /** What to write for the caret at [offset] of [file], or null when the intention has nothing to do there. */
    protected abstract fun plan(file: GoFile, offset: Int): GoEditPlan?

    override fun getText(): String = lastText ?: defaultText

    override fun getFamilyName(): String = defaultText

    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project)) return false
        val plan = plan(file, editor.caretModel.offset) ?: return false
        lastText = plan.text
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is GoFile) return
        val plan = plan(file, editor.caretModel.offset) ?: return
        val document = GoImportEdits.document(file) ?: return
        for (edit in plan.edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
        GoImportEdits.commit(file, document)
        if (plan.imports.isEmpty()) return
        for (path in plan.imports) GoImportInserter.addImport(file, document, path)
        GoImportEdits.commit(file, document)
    }
}

/** Shared PSI and text helpers of the intentions. */
internal object GoIntentionText {

    /** The leaf at [offset], or the one before it when the caret stands right after a token (`T{}<caret>`, `return<caret>`). */
    fun leafAt(file: GoFile, offset: Int): PsiElement? {
        val at = file.findElementAt(offset)
        if (at != null && !at.text.isBlank()) return at
        return if (offset > 0) file.findElementAt(offset - 1) ?: at else at
    }

    /** The spaces and tabs that start the line of [offset]. */
    fun indentAt(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }

    fun lineStart(text: CharSequence, offset: Int): Int {
        var i = minOf(offset, text.length) - 1
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }

    /**
     * [lines] (relative to [indent]) as their own lines before [anchor] (a `}` or a `default:`): at the start of the anchor's line when
     * it stands first on it, otherwise the anchor moves to a line of its own (`select {}` becomes three lines).
     */
    fun insertBefore(text: CharSequence, anchor: Int, indent: String, lines: List<String>): GoEditPlan.Edit {
        val block = lines.joinToString("") { "$indent$it\n" }
        val start = lineStart(text, anchor)
        if (text.subSequence(start, anchor).isBlank()) return GoEditPlan.Edit(start, start, block)
        var spaceStart = anchor
        while (spaceStart > 0 && (text[spaceStart - 1] == ' ' || text[spaceStart - 1] == '\t')) spaceStart--
        return GoEditPlan.Edit(spaceStart, anchor, "\n$block$indent")
    }

    /** The statement of a statement list that contains [element] (a block's or a clause's), not a header or an expression part. */
    fun statementAt(element: PsiElement): GoStatement? {
        var e: PsiElement? = element
        while (e != null && e !is PsiFile) {
            if (e is GoStatement && e.parent.let { it is GoBlock || it is GoExprCaseClause || it is GoTypeCaseClause || it is GoCommClause }) return e
            if (e is GoFunctionLit || e is GoFunctionOrMethodDeclaration) return null
            e = e.parent
        }
        return null
    }

    /** The signature of the function or function literal enclosing [element]. */
    fun enclosingSignature(element: PsiElement): GoSignatureType? {
        val service = GoSemanticService.getInstance(element.project)
        return when (val owner = GoPsiUtil.functionOwner(element)) {
            is GoFunctionOrMethodDeclaration -> service.declarationType(owner) as? GoSignatureType
            is GoFunctionLit -> service.typeOf(owner) as? GoSignatureType
            else -> null
        }
    }

    /** `return` with the zero values of [signature]'s results, [errorValue] for a last result of type `error`. */
    fun returnStatement(signature: GoSignatureType?, errorValue: String, source: GoSourceText): String {
        val results = signature?.results.orEmpty()
        if (results.isEmpty()) return "return"
        val values = results.mapIndexed { i, r -> if (i == results.lastIndex && GoZeroValues.isError(r.type)) errorValue else source.zero(r.type) }
        return "return " + values.joinToString(", ")
    }
}

/**
 * Types and package references as [file] spells them: a named type of another package is qualified by its import name, and a
 * package the file does not import yet is collected in [imports] (added after the edit). The `builtin` types are never qualified.
 */
class GoSourceText(private val file: GoFile) {
    val imports = LinkedHashSet<String>()
    private val directory = file.originalFile.virtualFile?.parent
    private val ownPath: String? by lazy { GoPackageModel.getInstance(file.project).packagePathOf(file) }

    /** Whether [element] is declared in the package of [file] (same directory): its unexported names are visible. */
    fun isOwnPackage(element: PsiElement?): Boolean {
        val other = element?.containingFile?.originalFile ?: return false
        if (other == file.originalFile) return true
        return directory != null && other.virtualFile?.parent == directory
    }

    /** Whether the package of [path] is the package of [file]. */
    fun isOwnPath(path: String?): Boolean = path != null && path == ownPath

    /** The qualifier of [named] in [file], or null when it needs none. */
    fun qualifier(named: GoNamedType): String? {
        val declarationFile = named.declaration.containingFile as? GoFile ?: return null
        if (declarationFile.packageName == "builtin" || isOwnPackage(declarationFile)) return null
        val path = named.pkgPath ?: return null
        if (isOwnPath(path)) return null
        return packageRef(path, declarationFile.packageName).takeIf { it.isNotEmpty() }
    }

    /** How [file] names the package [path] (its import name, "" for a dot import); a missing import is collected. */
    fun packageRef(path: String, name: String?): String {
        val spec = file.imports.firstOrNull { it.path == path && !it.isBlank }
        if (spec != null) return if (spec.isDot) "" else GoScopes.importName(spec)
        imports += path
        return name ?: path.substringAfterLast('/')
    }

    /** `pkg.` for [path], "" for the own package or a dot import. */
    fun prefix(path: String, name: String?): String = packageRef(path, name).let { if (it.isEmpty()) "" else "$it." }

    fun type(type: GoType): String = GoTypeRenderer.render(type) { qualifier(it) }

    fun zero(type: GoType): String = GoZeroValues.of(type, ::type)
}
