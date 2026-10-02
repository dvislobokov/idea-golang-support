package io.github.golangsupport.ide.navigation

import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProvider
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoTypeParameters
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import javax.swing.Icon

/**
 * Presentation of a Go declaration, shared by the structure view and Go to Symbol/Class.
 *
 * With [detailed] the text carries the signature or type (`f(a int, b string) error`, `X int`),
 * which reads the AST; without it only stub data is used (name, receiver type), so the goto popups
 * do not load the AST of every listed file. The location string is the package name followed by
 * the file path relative to its source or content root.
 */
class GoItemPresentation(
    private val element: GoNamedElement,
    private val detailed: Boolean = false,
    private val withReceiver: Boolean = true,
) : ItemPresentation {

    override fun getPresentableText(): String? =
        if (detailed) detailedText(element, withReceiver) else shortText(element)

    override fun getLocationString(): String? = locationString(element)

    override fun getIcon(unused: Boolean): Icon? = GoIdeIcons.forElement(element)

    companion object {
        /** Name, with the receiver type for methods (`T.M`); stub-only. */
        @JvmStatic
        fun shortText(element: GoNamedElement): String? {
            val name = element.name ?: return null
            if (element is GoMethodDeclaration) {
                val receiver = element.receiverTypeName ?: return name
                return "$receiver.$name"
            }
            return name
        }

        /** Signature-carrying text used by the structure view. */
        @JvmStatic
        fun detailedText(element: GoNamedElement, withReceiver: Boolean = true): String? {
            val name = element.name ?: return null
            return when (element) {
                is GoFunctionDeclaration -> name + typeParametersText(element.typeParameters) + signatureText(element.signature)
                is GoMethodDeclaration -> {
                    val receiver = element.receiverTypeName
                    val prefix = if (withReceiver && receiver != null) {
                        "(" + (if (element.isPointerReceiver) "*" else "") + receiver + ") "
                    } else {
                        ""
                    }
                    prefix + name + typeParametersText(element.typeParameters) + signatureText(element.signature)
                }
                is GoMethodSpec -> name + signatureText(element.signature)
                is GoTypeSpec -> name + typeParametersText(element.typeParameters)
                is GoFieldDefinition -> withType(name, (element.parent as? GoFieldDeclaration)?.type)
                is GoAnonymousFieldDefinition -> normalize(element.text)
                is GoVarDefinition -> withType(name, (element.parent as? GoVarSpec)?.type)
                is GoConstDefinition -> withType(name, (element.parent as? GoConstSpec)?.type)
                else -> name
            }
        }

        /** `pkg (dir/file.go)`, or `null` if the element is not in a Go file. */
        @JvmStatic
        fun locationString(element: PsiElement): String? {
            val file = element.containingFile as? GoFile ?: return null
            val path = relativePath(file) ?: file.name
            val pkg = file.packageName
            return if (pkg.isNullOrEmpty()) path else "$pkg ($path)"
        }

        /** `(a int, b string) error`: parameters and result with whitespace collapsed. */
        @JvmStatic
        fun signatureText(signature: GoSignature?): String {
            if (signature == null) return "()"
            val parameters = normalize(signature.parameters.text)
            val result = signature.result?.text?.let(::normalize)
            return if (result.isNullOrEmpty()) parameters else "$parameters $result"
        }

        @JvmStatic
        fun typeParametersText(typeParameters: GoTypeParameters?): String = typeParameters?.text?.let(::normalize).orEmpty()

        /** Collapses whitespace (comments included) and drops trailing commas before a closing bracket. */
        @JvmStatic
        fun normalize(text: String): String =
            text.replace(COMMENT, " ")
                .replace(WHITESPACE, " ")
                .replace(TRAILING_COMMA, "$1")
                .replace(OPEN_SPACE, "$1")
                .trim()

        private fun withType(name: String, type: PsiElement?): String = if (type == null) name else name + " " + normalize(type.text)

        private fun relativePath(file: GoFile): String? {
            val virtualFile = file.originalFile.virtualFile ?: return null
            val index = ProjectFileIndex.getInstance(file.project)
            val root = index.getSourceRootForFile(virtualFile)
                ?: index.getContentRootForFile(virtualFile)
                ?: index.getClassRootForFile(virtualFile)
                ?: return null
            return VfsUtilCore.getRelativePath(virtualFile, root)
        }

        private val COMMENT = Regex("//[^\\n]*|/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val WHITESPACE = Regex("\\s+")
        private val TRAILING_COMMA = Regex(",\\s*([)\\]}])")
        private val OPEN_SPACE = Regex("([(\\[{])\\s+")
    }
}

/** Supplies [GoItemPresentation] to every named Go element (their PSI does not override `getPresentation`). */
class GoItemPresentationProvider : ItemPresentationProvider<GoNamedElement> {
    override fun getPresentation(item: GoNamedElement): ItemPresentation = GoItemPresentation(item)
}
