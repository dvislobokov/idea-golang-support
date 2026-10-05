package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.completion.GoStructTagCompletion
import io.github.golangsupport.ide.completion.GoStructTagCompletion.Style
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.inspections.GoStructTagInspection
import io.github.golangsupport.ide.inspections.GoStructTags
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTag
import io.github.golangsupport.lang.psi.GoTypeSpec
import org.jetbrains.annotations.TestOnly

/** Struct tags as the tag intentions read and rewrite them: the name part of a key's value (before the first comma) per field. */
internal object GoTagText {

    /**
     * GoLand's four field name styles, in its order, with the label it shows: `full-name`, `full_name`, `FullName`, `fullName`.
     * Mirror of GoStructTagCompletion.nameStyles (added on the main tree in parallel), unify when merged.
     */
    val NAME_STYLES: List<Pair<Style, String>> = listOf(Style.KEBAB to "full-name", Style.SNAKE to "full_name", Style.AS_IS to "FullName", Style.LOWER_FIRST to "fullName")

    /** The one field name of [declaration] (an embedded type's name too); null for `A, B int`: a tag there names two fields. */
    fun fieldName(declaration: GoFieldDeclaration): String? =
        declaration.anonymousFieldDefinition?.name ?: declaration.fieldDefinitionList.singleOrNull()?.name

    /** The tag value of [declaration] (unquoted), or null. */
    fun tagValue(declaration: GoFieldDeclaration): String? = declaration.tag?.let { GoStructTagInspection.valueOf(it.stringLiteral) }

    /** The first pair of [key] in the tag of [declaration] with a name (not empty, not `-`). */
    fun namedPair(declaration: GoFieldDeclaration, key: String): GoStructTags.Pair? =
        tagValue(declaration)?.let { GoStructTags.parse(it).pairs.firstOrNull { p -> p.key == key } }?.takeIf { val n = it.value.substringBefore(','); n.isNotEmpty() && n != "-" }

    /** The style the fields of [struct] but [except] use for [key]. Mirror of GoStructTagCompletion.styleFor / samples, unify when merged. */
    fun styleFor(struct: GoStructType, key: String, except: GoFieldDeclaration?): Style {
        val samples = struct.fieldDeclarationList.filter { it !== except }.mapNotNull { d ->
            val name = fieldName(d) ?: return@mapNotNull null
            namedPair(d, key)?.let { name to it.value.substringBefore(',') }
        }
        return GoStructTagCompletion.detectStyle(key, samples)
    }

    /** The edit that sets the name of [key] in the tag of [declaration] to [name], keeping its options; null when nothing changes. */
    fun rename(declaration: GoFieldDeclaration, key: String, name: String): GoEditPlan.Edit? {
        val literal = declaration.tag?.stringLiteral ?: return null
        val tag = GoStructTagInspection.valueOf(literal) ?: return null
        val pair = GoStructTags.parse(tag).pairs.firstOrNull { it.key == key } ?: return null
        val old = pair.value.substringBefore(',')
        if (old == name) return null
        val written = key + ":" + GoStructTags.quote(name + pair.value.substring(old.length))
        val range = literal.textRange
        if (literal.rawString != null && '\r' !in literal.text) return GoEditPlan.Edit(range.startOffset + 1 + pair.start, range.startOffset + 1 + pair.end, written)
        if (literal.rawString != null) return null
        return GoEditPlan.Edit(range.startOffset, range.endOffset, GoStructTags.quote(tag.substring(0, pair.start) + written + tag.substring(pair.end)))
    }

    /** The key of the pair at [offset] in [tag], when the caret is on one. */
    fun keyAt(tag: GoTag, offset: Int): String? {
        val literal = tag.stringLiteral
        val value = GoStructTagInspection.valueOf(literal) ?: return null
        val rel = offset - literal.textRange.startOffset - 1
        return GoStructTags.parse(value).pairs.firstOrNull { rel >= it.start && rel <= it.end }?.key
    }

    fun nameKey(key: String): Boolean = key in GoStructTagCompletion.NAME_KEYS
}

/**
 * Change field name style in tags: on a struct tag (or the struct's `type` name / `struct` keyword), a popup of GoLand's four styles
 * (`full-name`, `full_name`, `FullName`, `fullName`); the chosen one rewrites the name of the key in every field of the struct, from
 * the field's name, keeping options such as `,omitempty`. The key is the one under the caret, otherwise the first name-like key
 * (json, yaml, xml, …) of the tag or of the struct.
 */
class GoChangeTagNameStyleIntention : IntentionAction {
    override fun getText(): String = "Change field name style in tags"

    override fun getFamilyName(): String = text

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project)) return false
        return target(file, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is GoFile) return
        val (_, key) = target(file, editor.caretModel.offset) ?: return
        val offset = editor.caretModel.offset
        fun apply(style: Style) = WriteCommandAction.runWriteCommandAction(project, text, null, {
            val (struct, k) = target(file, offset) ?: return@runWriteCommandAction
            GoEditText.apply(file, edits(struct, k, style))
        }, file)
        chooser?.let { choose -> apply(GoTagText.NAME_STYLES[choose(GoTagText.NAME_STYLES.map { it.second })].first); return }
        JBPopupFactory.getInstance().createPopupChooserBuilder(GoTagText.NAME_STYLES.map { it.second })
            .setTitle("Field Name Style for '$key'")
            .setItemChosenCallback { label -> GoTagText.NAME_STYLES.firstOrNull { it.second == label }?.let { apply(it.first) } }
            .createPopup()
            .showInBestPositionFor(editor)
    }

    companion object {
        /** Test hook: picks the index of the style instead of the popup. */
        @TestOnly @Volatile var chooser: ((List<String>) -> Int)? = null

        /** The struct and the key at [offset], when some field of the struct names itself under that key. */
        fun target(file: GoFile, offset: Int): Pair<GoStructType, String>? {
            val leaf = GoIntentionText.leafAt(file, offset) ?: return null
            val tag = PsiTreeUtil.getParentOfType(leaf, GoTag::class.java, false)
            val struct: GoStructType = when {
                tag != null -> (tag.parent as? GoFieldDeclaration)?.parent as? GoStructType
                leaf.parent is GoStructType && leaf == (leaf.parent as GoStructType).struct -> leaf.parent as GoStructType
                leaf.parent is GoTypeSpec && leaf == (leaf.parent as GoTypeSpec).identifier -> (leaf.parent as GoTypeSpec).type as? GoStructType
                else -> null
            } ?: return null
            val key = tag?.let { GoTagText.keyAt(it, offset) }?.takeIf(GoTagText::nameKey)
                ?: (listOfNotNull(tag?.parent as? GoFieldDeclaration) + struct.fieldDeclarationList).firstNotNullOfOrNull { d ->
                    GoTagText.tagValue(d)?.let { GoStructTags.parse(it).pairs.firstOrNull { p -> GoTagText.nameKey(p.key) }?.key }
                }
                ?: return null
            if (struct.fieldDeclarationList.none { GoTagText.fieldName(it) != null && GoTagText.namedPair(it, key) != null }) return null
            return struct to key
        }

        fun edits(struct: GoStructType, key: String, style: Style): List<GoEditPlan.Edit> = struct.fieldDeclarationList.mapNotNull { d ->
            val name = GoTagText.fieldName(d) ?: return@mapNotNull null
            if (GoTagText.namedPair(d, key) == null) return@mapNotNull null
            GoTagText.rename(d, key, style.apply(name))
        }
    }
}

/**
 * Update key value in tags: on a field whose tag names it differently from what its name gives in the style the other fields of the
 * struct use for that key (`UserID string `json:"id"`` among camelCase fields → `json:"userID"`), rewrites that name, keeping the
 * options. The key under the caret first, otherwise the first name-like key that differs.
 */
class GoUpdateTagKeyValueIntention : GoCodeActionIntention() {
    override val defaultText: String = "Update key value in tags"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(leaf, GoFieldDeclaration::class.java, false) ?: return null
        val struct = declaration.parent as? GoStructType ?: return null
        val name = GoTagText.fieldName(declaration) ?: return null
        val tag = declaration.tag ?: return null
        val keys = GoStructTags.parse(GoTagText.tagValue(declaration) ?: return null).pairs.map { it.key }.distinct().filter(GoTagText::nameKey)
        val atCaret = GoTagText.keyAt(tag, offset)
        for (key in keys.sortedByDescending { it == atCaret }) {
            if (GoTagText.namedPair(declaration, key) == null) continue
            val edit = GoTagText.rename(declaration, key, GoTagText.styleFor(struct, key, declaration).apply(name)) ?: continue
            return GoEditPlan(listOf(edit))
        }
        return null
    }
}
