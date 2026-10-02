package io.github.golangsupport.ide.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.project.api.GoPackageResolver

/**
 * `platform.backend.documentation.psiTargetProvider`: Quick Documentation (Ctrl+Q) and hover for
 * Go declarations, imports and package directories.
 */
class GoDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? = when {
        element is GoNamedElement && element.containingFile is GoFile -> GoDocumentationTarget(element)
        element is PsiDirectory && element.files.any { it is GoFile } -> GoDocumentationTarget(element)
        else -> null
    }
}

/**
 * Documentation of one Go element. Layout: the gopls-style definition (`func Println(a ...any) (n
 * int, err error)`), for types also their exported methods; the doc comment converted to HTML
 * ([GoDocHtml]); sections with the enclosing struct/interface, the package import path and the
 * file. Packages (import specs, package clauses, directories) show the package doc from `doc.go`
 * or the first file with a package comment.
 */
class GoDocumentationTarget(private val element: PsiElement) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let(::GoDocumentationTarget) }
    }

    override fun computePresentation(): TargetPresentation {
        val name = when (element) {
            is PsiDirectory -> element.name
            is GoNamedElement -> element.name ?: "?"
            else -> "?"
        }
        val icon = if (element is PsiDirectory || element is GoImportSpec || element is GoPackageClause) GoFileType.icon else GoIdeIcons.forElement(element)
        return TargetPresentation.builder(name).icon(icon).presentation()
    }

    override val navigatable: Navigatable? get() = element as? Navigatable

    override fun computeDocumentationHint(): String? = render(short = true)

    override fun computeDocumentation(): DocumentationResult? = render(short = false)?.let { DocumentationResult.documentation(it) }

    /** The HTML of this target; also used by tests as the golden content. */
    fun render(short: Boolean): String? {
        val pkg = packageOf(element)
        if (pkg != null) return renderPackage(pkg, short)
        val definition = GoDocSignature.definition(element, short) ?: return null
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append(StringUtil.escapeXmlEntities(definition))
        if (!short && element is GoTypeSpec) {
            val methods = GoDocSignature.methodLines(element)
            if (methods.isNotEmpty()) {
                sb.append("\n")
                for (m in methods) sb.append('\n').append(StringUtil.escapeXmlEntities(m))
            }
        }
        sb.append(DocumentationMarkup.DEFINITION_END)
        if (short) return sb.toString()
        GoDocComment.docText(element)?.let { doc ->
            sb.append(DocumentationMarkup.CONTENT_START).append(GoDocHtml.toHtml(doc)).append(DocumentationMarkup.CONTENT_END)
        }
        val sections = ArrayList<Pair<String, String>>()
        containerOf(element)?.let { sections += it }
        val file = element.containingFile as? GoFile
        if (file != null) {
            importPathOf(file)?.let { sections += "Package:" to "<code>${StringUtil.escapeXmlEntities(it)}</code>" }
            sections += "File:" to StringUtil.escapeXmlEntities(file.name)
        }
        appendSections(sb, sections)
        return sb.toString()
    }

    private fun renderPackage(dir: PsiDirectory, short: Boolean): String {
        val files = dir.files.filterIsInstance<GoFile>().filter { !it.isTestFile }
        val name = files.firstNotNullOfOrNull { it.packageName } ?: dir.name
        val importPath = files.firstOrNull()?.let(::importPathOf)
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("package ").append(StringUtil.escapeXmlEntities(name))
        if (importPath != null && importPath != name) sb.append(" // import \"").append(StringUtil.escapeXmlEntities(importPath)).append('"')
        sb.append(DocumentationMarkup.DEFINITION_END)
        if (short) return sb.toString()
        packageDoc(files)?.let { doc ->
            sb.append(DocumentationMarkup.CONTENT_START).append(GoDocHtml.toHtml(doc)).append(DocumentationMarkup.CONTENT_END)
        }
        val sections = ArrayList<Pair<String, String>>()
        if (importPath != null) sections += "Package:" to "<code>${StringUtil.escapeXmlEntities(importPath)}</code>"
        appendSections(sb, sections)
        return sb.toString()
    }

    private fun appendSections(sb: StringBuilder, sections: List<Pair<String, String>>) {
        if (sections.isEmpty()) return
        sb.append(DocumentationMarkup.SECTIONS_START)
        for ((header, value) in sections) {
            sb.append(DocumentationMarkup.SECTION_HEADER_START).append(header).append(DocumentationMarkup.SECTION_SEPARATOR)
                .append(value).append(DocumentationMarkup.SECTION_END).append("</tr>")
        }
        sb.append(DocumentationMarkup.SECTIONS_END)
    }

    /** `Struct: T` for fields, `Interface: I` for interface methods. */
    private fun containerOf(element: PsiElement): Pair<String, String>? {
        val (label, spec) = when (element) {
            is GoFieldDefinition, is GoAnonymousFieldDefinition -> "Struct:" to PsiTreeUtil.getParentOfType(element, GoTypeSpec::class.java)
            is GoMethodSpec -> "Interface:" to PsiTreeUtil.getParentOfType(element, GoTypeSpec::class.java)
            else -> return null
        }
        val name = spec?.name ?: return null
        return label to "<code>${StringUtil.escapeXmlEntities(name)}</code>"
    }

    /** The package's import path from the project model; null for files outside GOROOT, modules and GOPATH. */
    private fun importPathOf(file: GoFile): String? {
        val vf = file.originalFile.virtualFile ?: return null
        return GoPackageResolver.getInstance(file.project).importPathOf(vf)
    }

    companion object {
        /** The package directory documented by [element]: a directory, a resolved import, or the package clause's directory. */
        private fun packageOf(element: PsiElement): PsiDirectory? = when (element) {
            is PsiDirectory -> element
            is GoImportSpec -> element.reference?.resolve() as? PsiDirectory
            is GoPackageClause -> element.containingFile?.originalFile?.containingDirectory
            else -> null
        }

        /** The package doc: `doc.go` first, then the first file (by name) with a package comment. */
        @JvmStatic
        fun packageDoc(files: List<GoFile>): String? =
            files.sortedWith(compareBy<GoFile>({ it.name != "doc.go" }, { it.name }))
                .firstNotNullOfOrNull { f -> f.packageClause?.let(GoDocComment::docText) }
    }
}
