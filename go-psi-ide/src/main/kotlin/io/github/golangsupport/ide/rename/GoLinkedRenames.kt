package io.github.golangsupport.ide.rename

import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.NameSuggester
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.GoRenameChoice
import io.github.golangsupport.ide.completion.GoStructTagCompletion
import io.github.golangsupport.ide.completion.GoStructTagCompletion.Style
import io.github.golangsupport.ide.inspections.GoStructTagInspection
import io.github.golangsupport.ide.inspections.GoStructTags
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoStructType
import javax.swing.Icon

/**
 * A linked rename with GoLand's three choices ([GoRenameChoice]): Always is a factory without an option (the platform applies it to every
 * rename), Never is not applicable, Ask is the check box of the rename dialog, remembered for the renames without a dialog (in-place).
 */
abstract class GoLinkedRenamerFactory : AutomaticRenamerFactory {
    protected abstract val choice: GoRenameChoice
    protected abstract val option: String
    protected abstract fun applicable(element: PsiElement): Boolean

    override fun isApplicable(element: PsiElement): Boolean =
        choice != GoRenameChoice.NEVER && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project) && applicable(element)

    override fun getOptionName(): String? = if (choice == GoRenameChoice.ASK) option else null
}

/** A renamer whose new names are computed up front ([names]): `setRename` only changes a name already suggested, so they go through the suggester. */
abstract class GoLinkedRenamer : AutomaticRenamer() {
    protected val names = LinkedHashMap<PsiNamedElement, String>()

    protected fun suggest(oldName: String, newName: String) {
        myElements.addAll(names.keys)
        suggestAllNames(oldName, newName)
    }

    override fun suggestNameForElement(element: PsiNamedElement, suggester: NameSuggester, newClassName: String, oldClassName: String): String? = names[element] ?: newClassName
}

/**
 * "Rename tests": renaming `store.go` offers `store_test.go` (and the other way round), as GoLand's "When file is renamed: Rename
 * corresponding test or production file". Only a file of the same directory with the counterpart name is renamed.
 */
class GoTestFileRenamerFactory : GoLinkedRenamerFactory() {
    override val choice get() = GoIdeOptions.getInstance().renameTestFiles
    override val option = "Rename test/production file"

    override fun applicable(element: PsiElement): Boolean = element is GoFile && counterpart(element) != null

    override fun isEnabled(): Boolean = enabled

    override fun setEnabled(enabled: Boolean) {
        Companion.enabled = enabled
    }

    override fun createRenamer(element: PsiElement, newName: String, usages: Collection<UsageInfo>): AutomaticRenamer = object : GoLinkedRenamer() {
        init {
            val file = element as GoFile
            val other = counterpart(file)
            val otherName = other?.let { counterpartName(file.name, newName) }
            if (other != null && otherName != null) names[other] = otherName
            suggest(file.name, newName)
        }

        override fun getDialogTitle(): String = "Rename Test or Production File"
        override fun getDialogDescription(): String = "Rename the file with the following name to:"
        override fun entityName(): String = "File"
    }

    companion object {
        @Volatile private var enabled = true

        /** `a.go` -> `a_test.go`, `a_test.go` -> `a.go`; null for a name that is not a Go file. */
        fun counterpartName(name: String): String? = when {
            !name.endsWith(".go") -> null
            name.endsWith("_test.go") -> name.removeSuffix("_test.go").takeIf { it.isNotEmpty() }?.let { "$it.go" }
            else -> name.removeSuffix(".go") + "_test.go"
        }

        /** The new name of the counterpart when [oldName] becomes [newName]; null when the new name loses the kind (`a_test.go` -> `b.go`). */
        fun counterpartName(oldName: String, newName: String): String? = when {
            oldName.endsWith("_test.go") -> if (newName.endsWith("_test.go")) counterpartName(newName) else null
            newName.endsWith("_test.go") || !newName.endsWith(".go") -> null
            else -> counterpartName(newName)
        }

        fun counterpart(file: PsiFile): PsiFile? = counterpartName(file.name)?.let { file.containingDirectory?.findFile(it) }
    }
}

/**
 * "Rename struct tags": renaming a field `UserName` renames `json:"user_name"` to the same style of the new name, for every key that
 * names fields (json, yaml, xml, db, …). A tag name that is no style of the old field name (`json:"login"`) is left alone.
 */
class GoStructTagRenamerFactory : GoLinkedRenamerFactory() {
    override val choice get() = GoIdeOptions.getInstance().renameStructTags
    override val option = "Rename struct tags"

    override fun applicable(element: PsiElement): Boolean = element is GoFieldDefinition && tags(element, element.name.orEmpty()).isNotEmpty()

    override fun isEnabled(): Boolean = enabled

    override fun setEnabled(enabled: Boolean) {
        Companion.enabled = enabled
    }

    override fun createRenamer(element: PsiElement, newName: String, usages: Collection<UsageInfo>): AutomaticRenamer = object : GoLinkedRenamer() {
        init {
            val field = element as GoFieldDefinition
            for ((key, style) in tags(field, field.name.orEmpty())) {
                val declaration = field.parent as? GoFieldDeclaration ?: continue
                names[GoTagNameElement(declaration, key)] = style.apply(newName)
            }
            suggest(field.name.orEmpty(), newName)
        }

        // the tag value is not a name anyone refers to: no search, and no text occurrences renamed with it
        override fun findUsages(result: MutableList<UsageInfo>, searchInStringsAndComments: Boolean, searchInNonJavaFiles: Boolean,
                                unresolvedUsages: MutableList<in com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo>?,
                                allRenames: MutableMap<PsiElement, String>?) {}

        override fun getDialogTitle(): String = "Rename Struct Tags"
        override fun getDialogDescription(): String = "Rename the struct tags with the following names to:"
        override fun entityName(): String = "Tag"
    }

    companion object {
        @Volatile private var enabled = true

        /**
         * The keys of the tag of [field] that name it, with the style that gives the tag name from [fieldName]: the style the other fields
         * of the struct use for the key when it fits, else the first that does. Only a declaration of one field (`A, B int` shares a tag).
         */
        fun tags(field: GoFieldDefinition, fieldName: String): List<Pair<String, Style>> {
            val declaration = field.parent as? GoFieldDeclaration ?: return emptyList()
            if (declaration.fieldDefinitionList.size != 1 || fieldName.isEmpty()) return emptyList()
            val tag = declaration.tag?.stringLiteral?.let { GoStructTagInspection.valueOf(it) } ?: return emptyList()
            val struct = declaration.parent as? GoStructType
            return GoStructTags.parse(tag).pairs.filter { it.key in GoStructTagCompletion.NAME_KEYS }.mapNotNull { pair ->
                val name = pair.value.substringBefore(',')
                if (name.isEmpty() || name == "-") return@mapNotNull null
                val preferred = struct?.let { styleFor(it, pair.key, declaration) }
                val style = preferred?.takeIf { it.apply(fieldName) == name } ?: Style.entries.firstOrNull { it.apply(fieldName) == name } ?: return@mapNotNull null
                pair.key to style
            }
        }

        private fun styleFor(struct: GoStructType, key: String, except: GoFieldDeclaration): Style {
            val samples = struct.fieldDeclarationList.filter { it !== except }.mapNotNull { d ->
                val name = d.fieldDefinitionList.singleOrNull()?.name ?: return@mapNotNull null
                val value = d.tag?.stringLiteral?.let { GoStructTagInspection.valueOf(it) } ?: return@mapNotNull null
                GoStructTags.parse(value).lookup(key)?.substringBefore(',')?.takeIf { it.isNotEmpty() && it != "-" }?.let { name to it }
            }
            return GoStructTagCompletion.detectStyle(key, samples)
        }

        /** The text of [literal] with the name of [key] set to [name] (options kept); null when the tag cannot be rewritten. */
        fun renamedLiteral(literal: GoStringLiteral, key: String, name: String): String? {
            val tag = GoStructTagInspection.valueOf(literal) ?: return null
            val pair = GoStructTags.parse(tag).pairs.firstOrNull { it.key == key } ?: return null
            val old = pair.value.substringBefore(',')
            val written = key + ":" + GoStructTags.quote(name + pair.value.substring(old.length))
            val updated = tag.substring(0, pair.start) + written + tag.substring(pair.end)
            return if (literal.rawString != null && '`' !in updated && '\r' !in updated) "`$updated`" else GoStructTags.quote(updated)
        }
    }
}

/** The name of [key] in the tag of a field, as an element the platform can rename: `json:"user_name"` is "user_name". */
class GoTagNameElement(private val declaration: GoFieldDeclaration, val key: String) : FakePsiElement(), PsiNamedElement {
    override fun getParent(): PsiElement = declaration.tag ?: declaration
    override fun getContainingFile(): PsiFile = declaration.containingFile
    override fun isValid(): Boolean = declaration.isValid && declaration.tag != null
    override fun getTextRange() = declaration.tag?.textRange
    override fun getName(): String? = declaration.tag?.stringLiteral?.let { GoStructTagInspection.valueOf(it) }?.let { GoStructTags.parse(it).lookup(key) }?.substringBefore(',')
    override fun getPresentableText(): String = "$key:\"${name.orEmpty()}\""
    override fun getLocationString(): String? = declaration.fieldDefinitionList.singleOrNull()?.name
    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Annotationtype

    override fun setName(name: String): PsiElement {
        val literal = declaration.tag?.stringLiteral ?: return this
        val text = GoStructTagRenamerFactory.renamedLiteral(literal, key, name) ?: return this
        val dummy = GoElementFactory.createFileFromText(declaration.project, "package p\n\ntype _ struct {\n\tF int $text\n}\n")
        PsiTreeUtil.findChildOfType(dummy, GoStringLiteral::class.java)?.let { literal.replace(it) }
        return this
    }

    override fun equals(other: Any?): Boolean = other is GoTagNameElement && other.declaration == declaration && other.key == key
    override fun hashCode(): Int = declaration.hashCode() * 31 + key.hashCode()
}
