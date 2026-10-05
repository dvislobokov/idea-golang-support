package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse

/** PSI helpers of the import intentions: the spec at the caret, its alias token and the uses of its package in the file. */
internal object GoImportText {

    /** The import spec around the caret (its alias or its path). */
    fun specAt(file: GoFile, offset: Int): GoImportSpec? =
        PsiTreeUtil.getParentOfType(GoIntentionText.leafAt(file, offset), GoImportSpec::class.java, false)?.takeIf { it.path.isNotEmpty() && it.path != "C" }

    /** The alias token (identifier, `.` or `_`), or null. */
    fun aliasToken(spec: GoImportSpec): PsiElement? = (spec.node.findChildByType(GoTypes.IDENTIFIER) ?: spec.node.findChildByType(GoTypes.PERIOD))?.psi

    /** The qualifiers `pkg` of `pkg.Name` / `pkg.T` in [file] bound to [spec]. */
    fun qualifiedUses(file: GoFile, spec: GoImportSpec): List<GoReferenceExpression> {
        if (spec.isDot || spec.isBlank) return emptyList()
        val name = GoScopes.importName(spec)
        return PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java).filter { r ->
            r.expression == null && r.identifier?.text == name && selectedName(r) != null &&
                GoScopes.resolveName(r, name).firstOrNull().let { it is GoScopes.Target.Import && it.element == spec }
        }
    }

    /** The name selected from qualifier [r] (`Name` of `pkg.Name`), or null when [r] qualifies nothing. */
    fun selectedName(r: GoReferenceExpression): PsiElement? {
        val parent = r.parent
        return when {
            parent is GoReferenceExpression && parent.expression === r -> parent.identifier
            parent is GoTypeReferenceExpression && parent.referenceExpression === r -> parent.identifier
            else -> null
        }
    }

    /** The unqualified references of [file] that name members of the package of the dot import [spec]. */
    fun dotUses(file: GoFile, spec: GoImportSpec): List<PsiElement> {
        if (!spec.isDot) return emptyList()
        val model = GoPackageModel.getInstance(file.project)
        val members = model.resolveImport(spec.path, file)?.let(model::scopeOf) ?: return emptyList()
        fun bound(place: PsiElement, name: String?): Boolean {
            if (name.isNullOrEmpty()) return false
            val found = members.lookup(name)
            return found.isNotEmpty() && GoScopes.resolveName(place, name).any { it is GoScopes.Target.Declaration && it.element in found }
        }
        val out = ArrayList<PsiElement>()
        for (r in PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java)) if (r.expression == null && bound(r, r.identifier?.text)) out += r
        for (t in PsiTreeUtil.findChildrenOfType(file, GoTypeReferenceExpression::class.java)) if (t.referenceExpression == null && bound(t, t.identifier?.text)) out += t
        return out.sortedBy { it.textRange.startOffset }
    }

    /** Whether [name] would mean something else than the import at [place]: a declaration other than a builtin is visible there. */
    fun taken(place: PsiElement, name: String): Boolean =
        GoScopes.resolveName(place, name).any { it !is GoScopes.Target.Declaration || !GoUniverse.isBuiltinDeclaration(it.element) }

    /** The edit that sets the alias of [spec] to [alias] ("" removes it with the space after it). */
    fun setAlias(spec: GoImportSpec, alias: String): GoEditPlan.Edit {
        val token = aliasToken(spec)
        val start = spec.textRange.startOffset
        if (token == null) return GoEditPlan.Edit(start, start, "$alias ")
        val text = spec.containingFile.node.chars
        if (alias.isNotEmpty()) return GoEditPlan.Edit(token.textRange.startOffset, token.textRange.endOffset, alias)
        var end = token.textRange.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return GoEditPlan.Edit(token.textRange.startOffset, end, "")
    }

    fun hasComments(spec: GoImportSpec): Boolean = PsiTreeUtil.findChildOfType(spec, PsiComment::class.java) != null
}

/** Import for side-effects: `"pkg"` → `_ "pkg"` for an import the file does not use (an unused import or a used one would break). */
class GoImportForSideEffectsIntention : GoCodeActionIntention() {
    override val defaultText: String = "Import for side-effects"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val spec = GoImportText.specAt(file, offset) ?: return null
        if (spec.isBlank || GoImportText.hasComments(spec)) return null
        if (GoImportText.qualifiedUses(file, spec).isNotEmpty() || GoImportText.dotUses(file, spec).isNotEmpty()) return null
        return GoEditPlan(listOf(GoImportText.setAlias(spec, "_")))
    }
}

/**
 * Add import alias: `"pkg"` → `name "pkg"` with the package name as the alias, then a rename-like template over the alias and the
 * qualifiers of its uses, so typing another name renames them together.
 */
class GoAddImportAliasIntention : GoCodeActionIntention() {
    override val defaultText: String = "Add import alias"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val spec = GoImportText.specAt(file, offset) ?: return null
        if (spec.alias != null || GoImportText.hasComments(spec)) return null
        val name = GoScopes.importName(spec).takeIf { it.isNotEmpty() } ?: return null
        return GoEditPlan(listOf(GoImportText.setAlias(spec, name)))
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is GoFile) return
        val start = GoImportText.specAt(file, editor.caretModel.offset)?.textRange?.startOffset ?: return
        super.invoke(project, editor, file)
        if (IntentionPreviewUtils.isIntentionPreviewActive()) return
        val document = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val spec = PsiTreeUtil.getParentOfType(file.findElementAt(start), GoImportSpec::class.java, false) ?: return
        val alias = GoImportText.aliasToken(spec) ?: return
        val uses = GoImportText.qualifiedUses(file, spec)
        editor.caretModel.moveToOffset(alias.textRange.startOffset)
        val builder = TemplateBuilderImpl(file)
        builder.replaceElement(alias, "ALIAS", ConstantNode(alias.text), true)
        uses.forEachIndexed { i, use -> builder.replaceElement(use, "ALIAS_$i", "ALIAS", false) }
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
        builder.run(editor, true)
    }
}

/**
 * Add dot import alias: `"pkg"` → `. "pkg"`, and `pkg.Name` → `Name` in the file. Not offered when a dropped qualifier would let the
 * name mean something else (a declaration of the package or a local of that name).
 */
class GoAddDotImportAliasIntention : GoCodeActionIntention() {
    override val defaultText: String = "Add dot import alias"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val spec = GoImportText.specAt(file, offset) ?: return null
        if (spec.isDot || spec.isBlank || GoImportText.hasComments(spec)) return null
        val edits = arrayListOf(GoImportText.setAlias(spec, "."))
        for (use in GoImportText.qualifiedUses(file, spec)) {
            val selected = GoImportText.selectedName(use) ?: return null
            if (GoImportText.taken(selected.parent, selected.text)) return null
            edits += GoEditPlan.Edit(use.textRange.startOffset, selected.textRange.startOffset, "")
        }
        return GoEditPlan(edits)
    }
}

/** Remove dot import alias: `. "pkg"` → `"pkg"`, and the names of the package used in the file get the `pkg.` qualifier. */
class GoRemoveDotImportAliasIntention : GoCodeActionIntention() {
    override val defaultText: String = "Remove dot import alias"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val spec = GoImportText.specAt(file, offset) ?: return null
        if (!spec.isDot || GoImportText.hasComments(spec)) return null
        val name = GoScopes.importName(spec).takeIf { it.isNotEmpty() && it != "." } ?: return null
        val edits = arrayListOf(GoImportText.setAlias(spec, ""))
        for (use in GoImportText.dotUses(file, spec)) {
            if (GoImportText.taken(use, name)) return null
            edits += GoEditPlan.Edit(use.textRange.startOffset, use.textRange.startOffset, "$name.")
        }
        return GoEditPlan(edits)
    }
}
