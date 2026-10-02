package io.github.golangsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec

/** What a package-level declaration (or a member of a type) is, as the tools of the plugin tell them apart: icons, generators, gopls requests. */
enum class GoDeclarationKind(val title: String) {
    FUNCTION("func"),
    METHOD("method"),
    STRUCT("struct"),
    INTERFACE("interface"),
    TYPE("type"),
    FIELD("field"),
    INTERFACE_METHOD("method"),
    CONST("const"),
    VAR("var");

    val isType: Boolean get() = this == STRUCT || this == INTERFACE || this == TYPE

    /** The declarations of the PSI that have a kind; locals, parameters, imports, labels and type parameters have none. */
    companion object {
        fun of(element: PsiElement): GoDeclarationKind? = when (element) {
            is GoFunctionDeclaration -> FUNCTION
            is GoMethodDeclaration -> METHOD
            is GoTypeSpec -> when (element.type) {
                is GoStructType -> STRUCT
                is GoInterfaceType -> INTERFACE
                else -> TYPE
            }
            is GoMethodSpec -> INTERFACE_METHOD
            is GoFieldDefinition -> FIELD
            is GoVarDefinition -> if (isPackageLevel(element)) VAR else null
            is GoConstDefinition -> if (isPackageLevel(element)) CONST else null
            else -> null
        }

        /** [element] when it is a declaration with a kind. */
        fun declaration(element: PsiElement?): GoNamedElement? = (element as? GoNamedElement)?.takeIf { of(it) != null }

        /** The declaration whose name is this identifier leaf; null for any other token, and for the name of a local. */
        fun ofName(leaf: PsiElement?): GoNamedElement? {
            if (leaf == null || leaf.elementType != GoTypes.IDENTIFIER) return null
            return declaration(leaf.parent)?.takeIf { it.nameIdentifier == leaf }
        }

        /** The innermost declaration with a kind around [offset] (its name or anything inside it), as Go to Test and Go to Super want it. */
        fun at(file: PsiFile, offset: Int): GoNamedElement? {
            var element: PsiElement? = file.findElementAt(offset)
            while (element != null && element !is PsiFile) {
                declaration(element)?.let { return it }
                element = element.parent
            }
            return null
        }

        /** A var or const definition outside every function body. */
        private fun isPackageLevel(element: PsiElement): Boolean = element.parentOfType<GoBlock>() == null && element.parentOfType<GoFunctionLit>() == null
    }
}

/**
 * A declaration as a value, for the code that writes text from it (the generators, the counts of gopls) and has to be testable without
 * the PSI it came from: [of] builds it from a PSI declaration. The scanner of the catalogue builds the same from files outside the project.
 */
class GoDeclarationInfo(
    val kind: GoDeclarationKind,
    val name: String,
    val nameRange: TextRange,
    /** From the keyword (or, in a group, from the name) to the end of the declaration: the doc comment is not in it. */
    val range: TextRange,
    /** The type of the receiver of a method, without the pointer and the type parameters. */
    val receiver: String? = null,
    /** Parameters and results of a function, the type of a field: what follows the name, in one line. */
    val signature: String? = null,
    /** The body of a function or of a struct / interface type, with its braces. */
    val body: TextRange? = null,
    val children: List<GoDeclarationInfo> = emptyList(),
) {
    val isExported: Boolean get() = name.firstOrNull()?.isUpperCase() == true

    /** `(Server) Start(ctx context.Context) error`, `Port int`. */
    val presentation: String
        get() = (if (receiver != null) "($receiver) " else "") + name + when {
            signature == null -> ""
            kind == GoDeclarationKind.FUNCTION || kind == GoDeclarationKind.METHOD || kind == GoDeclarationKind.INTERFACE_METHOD -> signature
            else -> " $signature"
        }

    companion object {
        /** The value of a PSI declaration with a kind ([GoDeclarationKind.of]); null for anything else or a declaration without a name. Read action. */
        fun of(element: PsiElement): GoDeclarationInfo? {
            val kind = GoDeclarationKind.of(element) ?: return null
            val named = element as GoNamedElement
            val name = named.name ?: return null
            val identifier = named.nameIdentifier ?: return null
            return when (element) {
                is GoFunctionOrMethodDeclaration -> GoDeclarationInfo(
                    kind, name, identifier.textRange, TextRange(codeStart(element), element.textRange.endOffset), (element as? GoMethodDeclaration)?.receiverTypeName,
                    oneLine(element.typeParameters?.text.orEmpty() + element.signature?.text.orEmpty()).ifEmpty { null }, element.block?.textRange,
                )
                is GoTypeSpec -> typeInfo(element, kind, name, identifier)
                is GoMethodSpec -> GoDeclarationInfo(kind, name, identifier.textRange, element.textRange, signature = element.signature?.text?.let(::oneLine))
                is GoFieldDefinition -> GoDeclarationInfo(kind, name, identifier.textRange, element.textRange, signature = (element.parent as? GoFieldDeclaration)?.type?.text?.let(::oneLine))
                is GoVarDefinition -> GoDeclarationInfo(kind, name, identifier.textRange, specRange(element), signature = (element.parent as? GoVarSpec)?.type?.text?.let(::oneLine))
                is GoConstDefinition -> GoDeclarationInfo(kind, name, identifier.textRange, specRange(element), signature = (element.parent as? GoConstSpec)?.type?.text?.let(::oneLine))
                else -> null
            }
        }

        /** The package-level declarations of [file] in the order of the text, a type with its fields or methods as children. Read action. */
        fun topLevel(file: GoFile): List<GoDeclarationInfo> = CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(topLevelElements(file).mapNotNull(::of), file)
        }

        /** [topLevel] depth first: the children of a type right after it. */
        fun all(file: GoFile): List<GoDeclarationInfo> = topLevel(file).flatMap { listOf(it) + it.children }

        /** The package-level declarations of [file] with a kind, in the order of the text. */
        fun topLevelElements(file: GoFile): List<GoNamedElement> = buildList {
            for (child in file.children) when (child) {
                is GoFunctionOrMethodDeclaration -> add(child)
                is GoTypeDeclaration -> addAll(child.typeSpecList)
                is GoVarDeclaration -> child.varSpecList.forEach { spec -> spec.varDefinitionList.filter { it.name != "_" }.forEach(::add) }
                is GoConstDeclaration -> child.constSpecList.forEach { spec -> spec.constDefinitionList.filter { it.name != "_" }.forEach(::add) }
            }
        }

        private fun typeInfo(spec: GoTypeSpec, kind: GoDeclarationKind, name: String, identifier: PsiElement): GoDeclarationInfo {
            val declaration = spec.parent as? GoTypeDeclaration
            // the `type` keyword of a spec of its own, the name in a `type (...)` group
            val start = if (declaration != null && declaration.lparen == null) declaration.type_.textRange.startOffset else identifier.textRange.startOffset
            val range = TextRange(start, spec.textRange.endOffset)
            return when (val type = spec.type) {
                is GoStructType -> {
                    val fields = GoStructPsi.fields(type).filter { it.name != "_" }.map { field ->
                        val nameRange = (field.element.nameIdentifier ?: field.element).textRange
                        GoDeclarationInfo(GoDeclarationKind.FIELD, field.name, nameRange, TextRange(nameRange.startOffset, field.declaration.textRange.endOffset), signature = field.typeText.takeUnless { field.embedded })
                    }
                    GoDeclarationInfo(kind, name, identifier.textRange, range, body = GoStructPsi.bodyRange(type), children = fields)
                }
                is GoInterfaceType -> {
                    val methods = type.methodSpecList.mapNotNull { method -> of(method) }
                    GoDeclarationInfo(kind, name, identifier.textRange, range, body = GoStructPsi.bodyRange(type), children = methods)
                }
                else -> GoDeclarationInfo(kind, name, identifier.textRange, range, signature = type?.text?.let(::oneLine))
            }
        }

        /** Where the code of a declaration starts: past its doc comment. */
        private fun codeStart(element: PsiElement): Int =
            generateSequence(element.firstChild) { it.nextSibling }.firstOrNull { it !is PsiComment && it !is PsiWhiteSpace }?.textRange?.startOffset ?: element.textRange.startOffset

        private fun specRange(element: PsiElement): TextRange = (element.parent ?: element).let { TextRange(codeStart(it), it.textRange.endOffset) }

        /** A type or a signature written over several lines, as one line without comments. */
        fun oneLine(text: String): String = GoInterfaces.tidy(GoInterfaces.stripComments(text))
    }
}

/** An import of a file: the unquoted path, the alias as written (`_`, `.` included) and the range from the alias (or the path) to the path's end. */
class GoImport(val path: String, val alias: String?, val range: TextRange)
