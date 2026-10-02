package io.github.golangsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTag
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoType

/**
 * Structs as the PSI of go-psi sees them, for the generators, struct tags and field alignment: the fields with their names, the type as
 * written, the type as the type checker knows it, the tag, whether they are embedded. Needs read access.
 */
object GoStructPsi {
    /** One name of a field declaration (`a, b int` gives two), or an embedded field, whose [name] is that of its type. */
    class Field(
        val name: String,
        /** The type as written, on one line (`*pkg.T` for an embedded pointer). */
        val typeText: String,
        /** The tag literal with its quotes, as written; null without one. */
        val tag: String?,
        val embedded: Boolean,
        val doc: String?,
        /** The field definition, or the anonymous field definition of an embedded field. */
        val element: GoNamedElement,
        val declaration: GoFieldDeclaration,
    ) {
        val exported: Boolean get() = name.firstOrNull()?.isUpperCase() == true

        /** The type the checker gives the field; unknown types stay unknown. Resolves on first use. */
        val type: GoType by lazy { GoSemanticService.getInstance(element.project).declarationType(element) }

        /** The keys the tag has already (`json`, `db`), from its value. */
        val tagKeys: Set<String> get() = tagValue?.let { TAG_KEY.findAll(it).map { m -> m.groupValues[1] }.toSet() }.orEmpty()

        /** The text between the quotes of the tag. */
        val tagValue: String? get() = tag?.takeIf { it.length >= 2 }?.substring(1, tag.length - 1)
    }

    private val TAG_KEY = Regex("""(\w+):"""")

    /** The fields of [struct] in declaration order, the names of one declaration each on its own. */
    fun fields(struct: GoStructType): List<Field> = struct.fieldDeclarationList.flatMap(::fieldsOf)

    fun fieldsOf(declaration: GoFieldDeclaration): List<Field> {
        val tag = declaration.tag?.text
        val anonymous = declaration.anonymousFieldDefinition
        if (anonymous != null) {
            val name = anonymous.name ?: return emptyList()
            return listOf(Field(name, oneLine(anonymous.text), tag, true, anonymous.docText, anonymous, declaration))
        }
        val typeText = oneLine(declaration.type?.text ?: return emptyList())
        return declaration.fieldDefinitionList.mapNotNull { definition ->
            definition.name?.let { Field(it, typeText, tag, false, definition.docText, definition, declaration) }
        }
    }

    /** The struct type of [spec]; null for any other kind of type. */
    fun structOf(spec: GoTypeSpec): GoStructType? = spec.type as? GoStructType

    /** The top-level type spec the caret stands in, or on its keyword or name; a type declared inside a function is not one. */
    fun typeSpecAt(file: PsiFile, offset: Int): GoTypeSpec? {
        for (at in intArrayOf(offset, offset - 1)) {
            if (at < 0) continue
            val leaf = file.findElementAt(at) ?: continue
            val spec = PsiTreeUtil.getParentOfType(leaf, GoTypeSpec::class.java, false)
                ?: PsiTreeUtil.getParentOfType(leaf, GoTypeDeclaration::class.java, false)?.typeSpecList?.singleOrNull()
            if (spec != null) return spec.takeIf(::isTopLevel)
        }
        return null
    }

    /** The struct type spec around [offset]. */
    fun structSpecAt(file: PsiFile, offset: Int): GoTypeSpec? = typeSpecAt(file, offset)?.takeIf { structOf(it) != null }

    private fun isTopLevel(spec: GoTypeSpec): Boolean = spec.parent is GoTypeDeclaration && spec.parent.parent is GoFile

    /** The innermost struct type (named or not, a field of a function too) around [element]. */
    fun structAround(element: PsiElement): GoStructType? = PsiTreeUtil.getParentOfType(element, GoStructType::class.java, false)

    /** The tag the element is in, with its field declaration; null outside every tag. */
    fun tagAround(element: PsiElement): GoTag? = PsiTreeUtil.getParentOfType(element, GoTag::class.java, false)

    /** `{ ... }` of a struct or interface type, braces included; null while the closing brace is missing. */
    fun bodyRange(type: PsiElement): TextRange? {
        val (open, close) = when (type) {
            is GoStructType -> type.lbrace to type.rbrace
            is GoInterfaceType -> type.lbrace to type.rbrace
            else -> return null
        }
        return TextRange((open ?: return null).textRange.startOffset, (close ?: return null).textRange.endOffset)
    }

    /** [spec] as a value for the generators: name, ranges, body and, for a struct, the fields (an embedded one without a signature). */
    fun infoOf(spec: GoTypeSpec): GoDeclarationInfo? = GoDeclarationInfo.of(spec)

    /** A type written over several lines, as one line: what a parameter list or a dialog shows. */
    private fun oneLine(text: String): String = if ('\n' in text) GoInterfaces.tidy(GoInterfaces.stripComments(text)) else text
}
