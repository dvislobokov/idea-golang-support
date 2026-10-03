package io.github.golangsupport.ide.directives

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec

/**
 * What makes `go build` reject a `//go:embed` comment, with go's wording: a malformed or invalid pattern, a pattern that matches no
 * file (or a directory without embeddable files), a directive not directly above a package-level `var`, a file that does not import
 * "embed" (fix: add the blank import for string / []byte variables, the plain one for `embed.FS`).
 */
class GoEmbedDirectiveInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is PsiComment || !GoEmbed.isEmbed(element.text)) return
        val declaration = placedVar(element)
        if (declaration == null) {
            holder.registerProblem(element, "misplaced //go:embed directive", ProblemHighlightType.GENERIC_ERROR)
            return
        }
        if (file.imports.none { it.path == "embed" }) {
            val plain = declaration.varSpecList.any { it.type?.text?.trim()?.endsWith("FS") == true }
            holder.registerProblem(element, "go:embed only allowed in Go files that import \"embed\"", ProblemHighlightType.GENERIC_ERROR, GoAddEmbedImportFix(plain))
        }
        val (patterns, quoting) = GoEmbed.patterns(element.text)
        if (quoting != null) holder.registerProblem(element, "invalid quoted string in //go:embed: ${quoting.substringAfter(": ")}", ProblemHighlightType.GENERIC_ERROR)
        if (patterns.isEmpty() && quoting == null) holder.registerProblem(element, "usage: //go:embed pattern...", ProblemHighlightType.GENERIC_ERROR)
        val dir = file.originalFile.virtualFile?.parent ?: return
        for (p in patterns) {
            val invalid = GoEmbed.validate(p)
            if (invalid != null) {
                holder.registerProblem(element, invalid, ProblemHighlightType.GENERIC_ERROR, p.range)
                continue
            }
            val matches = GoEmbed.match(dir, p.pattern)
            if (matches.isEmpty()) {
                holder.registerProblem(element, "pattern ${p.raw}: no matching files found", ProblemHighlightType.GENERIC_ERROR, p.range)
                continue
            }
            if (matches.none { it.isDirectory && !GoEmbed.hasEmbeddable(it, p.all) }) continue
            holder.registerProblem(
                element, "pattern ${p.raw}: cannot embed directory ${p.pattern}: contains no embeddable files", ProblemHighlightType.GENERIC_ERROR, p.range,
            )
        }
    }

    /** The package-level `var` declaration the directive is attached to: the next code after it, blank lines and comments aside. */
    private fun placedVar(comment: PsiComment): GoVarDeclaration? {
        var leaf = PsiTreeUtil.nextLeaf(comment)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment)) leaf = PsiTreeUtil.nextLeaf(leaf)
        leaf ?: return null
        val declaration = PsiTreeUtil.getParentOfType(leaf, GoVarDeclaration::class.java, false) ?: return null
        if (PsiTreeUtil.getParentOfType(declaration, GoBlock::class.java) != null) return null
        val spec = PsiTreeUtil.getParentOfType(leaf, GoVarSpec::class.java, false)
        val startsDeclaration = leaf.text == "var" && leaf.parent === declaration
        val startsSpec = spec != null && spec.parent === declaration && PsiTreeUtil.firstChild(spec) === leaf
        return declaration.takeIf { startsDeclaration || startsSpec }
    }
}

/** Adds `import _ "embed"` ([plain] false) or `import "embed"`. */
class GoAddEmbedImportFix(private val plain: Boolean) : LocalQuickFix {
    override fun getFamilyName(): String = "Add import \"embed\""

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile as? GoFile ?: return
        val document = GoImportEdits.document(file) ?: return
        GoImportInserter.addImport(file, document, "embed", if (plain) null else "_")
        GoImportEdits.commit(file, document)
    }
}
