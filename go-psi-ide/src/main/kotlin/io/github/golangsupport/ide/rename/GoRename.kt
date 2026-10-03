package io.github.golangsupport.ide.rename

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.project.Project
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.rename.RenameInputValidatorEx
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.refactoring.GoIntroduceConstantHandler
import io.github.golangsupport.ide.refactoring.GoIntroduceVariableHandler
import io.github.golangsupport.ide.refactoring.GoSafeDeleteProcessor
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause

/** Go identifiers and keywords for rename and other refactorings. */
class GoNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?): Boolean = name in KEYWORDS

    override fun isIdentifier(name: String, project: Project?): Boolean = isValidIdentifier(name)

    companion object {
        @JvmField
        val KEYWORDS: Set<String> = setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func",
            "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct",
            "switch", "type", "var",
        )

        /** `[\p{L}_][\p{L}\p{Nd}_]*` and not a keyword (the Go spec's identifier). */
        @JvmStatic
        fun isValidIdentifier(name: String): Boolean {
            if (name.isEmpty() || name in KEYWORDS) return false
            var i = 0
            var first = true
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val ok = cp == '_'.code || Character.isLetter(cp) || (!first && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt())
                if (!ok) return false
                first = false
                i += Character.charCount(cp)
            }
            return true
        }
    }
}

/** Rejects keywords and non-identifiers in the rename dialog of Go declarations, with a reason. */
class GoRenameInputValidator : RenameInputValidatorEx {
    override fun getPattern(): ElementPattern<out PsiElement> = PlatformPatterns.psiElement(GoNamedElement::class.java)

    // `package _` is not allowed by the spec.
    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean =
        GoNamesValidator.isValidIdentifier(newName) && !(element is GoPackageClause && newName == "_")

    override fun getErrorMessage(newName: String, project: Project): String? = when {
        newName in GoNamesValidator.KEYWORDS -> "'$newName' is a Go keyword"
        !GoNamesValidator.isValidIdentifier(newName) -> "'$newName' is not a valid Go identifier"
        else -> null
    }
}

/**
 * Rename modes: in-place for declarations visible only in one function or file (locals,
 * parameters, receivers, labels, imports); the dialog for package-level declarations.
 * While [GoIdeFeature.RENAME] is off no in-place rename is offered: the platform's
 * `VariableInplaceRenameHandler` would otherwise stand next to the host's other rename
 * handler (a language server's) and the registry would ask the user to choose between them.
 */
class GoRefactoringSupportProvider : RefactoringSupportProvider() {
    // Any Go element while RENAME is on: Introduce Variable / Constant ask with the leaf at the caret.
    override fun isAvailable(context: PsiElement): Boolean =
        context is GoNamedElement || (context.language == GoLanguage && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, context.project))

    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        element is GoNamedElement && element !is GoPackageClause && element.useScope is LocalSearchScope &&
            GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project) &&
            // An unaliased import of a project package renames the package (the dialog substitutes its clause).
            !(element is GoImportSpec && GoRenamePackageProcessor.importedClause(element) != null)

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = false

    // Introduce Variable / Constant and Safe Delete answer only while the host gives the refactorings to the PSI (RENAME is on).
    override fun getIntroduceVariableHandler(): RefactoringActionHandler = GoIntroduceVariableHandler()

    override fun getIntroduceVariableHandler(element: PsiElement?): RefactoringActionHandler? = getIntroduceVariableHandler().takeIf { enabled(element) }

    override fun getIntroduceConstantHandler(): RefactoringActionHandler = GoIntroduceConstantHandler()

    override fun isSafeDeleteAvailable(element: PsiElement): Boolean = GoSafeDeleteProcessor.isSupported(element) && enabled(element)

    private fun enabled(element: PsiElement?): Boolean = element == null || GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)
}
