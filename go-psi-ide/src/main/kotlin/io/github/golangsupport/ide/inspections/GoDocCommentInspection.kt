package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition

/**
 * Doc comments of exported package-level declarations (golint / revive `exported`; opt-in, off by default):
 * - no doc comment on an exported function, method (of an exported receiver type), type, const or var;
 * - a doc comment that does not start with the name (`Name`, `A Name`, `An Name`, `The Name`; a `Deprecated:` paragraph is ignored).
 * A comment on a `const (...)` / `var (...)` / `type (...)` group counts for its specs, which need none of their own; the group comment
 * itself is not checked for the form. Skipped: `_test.go` files, `main` packages, generated files (`// Code generated ... DO NOT EDIT.`).
 */
class GoDocCommentInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val target = when (element) {
            is GoFunctionDeclaration -> element.name?.let { Target(element, element, "function", it, it, null) }
            is GoMethodDeclaration -> {
                val receiver = element.receiverTypeName
                val name = element.name
                if (receiver == null || name == null || !isExported(receiver)) null else Target(element, element, "method", name, "$receiver.$name", null)
            }
            is GoTypeSpec -> element.name?.let { Target(element, element, "type", it, it, element.parent) }
            is GoConstDefinition -> specTarget(element, "const")
            is GoVarDefinition -> specTarget(element, "var")
            else -> null
        } ?: return
        if (!isExported(target.name) || skipFile(file)) return
        val identifier = target.element.nameIdentifier ?: return
        val hasText = docText(target.element) != null
        if (!target.grouped) {
            if (!hasText) missing(holder, identifier, target) else checkForm(holder, target, target.element.docComment)
            return
        }
        // A group member: its own comment is checked for the form; the group's comment only counts for "missing".
        val own = GoDocFix.leadingComment(target.own)
        if (own != null) {
            if (!hasText) missing(holder, identifier, target) else checkForm(holder, target, own)
        } else if (!hasText) {
            missing(holder, identifier, target)
        }
    }

    /** [element] is the checked name, [own] carries its own comment (the spec or the declaration), [group] is the `(...)` declaration or null. */
    private class Target(val element: GoNamedElement, val own: PsiElement, val kind: String, val name: String, val display: String, declaration: PsiElement?) {
        val grouped: Boolean = declaration != null && GoDocFix.isGroup(declaration)
    }

    /** Only the first name of a spec is checked (golint). */
    private fun specTarget(definition: GoNamedElement, kind: String): Target? {
        val spec = definition.parent ?: return null
        if (spec.children.firstOrNull { it is GoConstDefinition || it is GoVarDefinition } !== definition) return null
        val name = definition.name ?: return null
        return Target(definition, spec, kind, name, name, spec.parent)
    }

    private fun missing(holder: ProblemsHolder, identifier: PsiElement, target: Target) {
        val hint = if (target.grouped) " (or a comment on this block)" else ""
        holder.registerProblem(
            identifier, "exported ${target.kind} ${target.display} should have comment$hint or be unexported",
            ProblemHighlightType.WEAK_WARNING, GoAddDocCommentFix(target.name),
        )
    }

    private fun checkForm(holder: ProblemsHolder, target: Target, comment: PsiComment?) {
        comment ?: return
        val text = docText(target.element) ?: return
        if (startsWithName(text, target.name)) return
        val fixes = if (comment.text.startsWith("//")) arrayOf<LocalQuickFix>(GoStartCommentWithNameFix(target.name)) else emptyArray()
        holder.registerProblem(
            comment, "comment on exported ${target.kind} ${target.display} should be of the form \"${target.name} ...\"",
            ProblemHighlightType.WEAK_WARNING, *fixes,
        )
    }

    /** The doc text of [element] when it is not just directives or blank, else null. */
    private fun docText(element: GoNamedElement): String? = element.docText?.takeIf { it.isNotBlank() }

    companion object {
        fun isExported(name: String): Boolean = name.firstOrNull()?.isUpperCase() == true

        private fun skipFile(file: GoFile): Boolean = file.isTestFile || file.packageName == "main" || GoAnalysisScope.isGenerated(file)

        /** Whether [text] starts with [name], optionally after `A` / `An` / `The`; a `Deprecated:` first paragraph passes. */
        fun startsWithName(text: String, name: String): Boolean {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("Deprecated:")) return true
            return listOf("", "A ", "An ", "The ").any { prefix ->
                trimmed.startsWith(prefix + name) && trimmed.getOrNull(prefix.length + name.length)?.let { !it.isLetterOrDigit() && it != '_' } != false
            }
        }
    }
}

/** Shared PSI helpers of the doc comment fixes. */
internal object GoDocFix {
    /** `const (...)` and friends: a declaration with a parenthesis. */
    fun isGroup(declaration: PsiElement): Boolean = declaration.node.findChildByType(GoTypes.LPAREN) != null

    /** The first comment among the leading children of [holder] (the doc comment is bound into the declaration or spec). */
    fun leadingComment(holder: PsiElement): PsiComment? {
        var child = holder.firstChild
        while (child != null) {
            when (child) {
                is PsiComment -> return child
                is PsiWhiteSpace -> {}
                else -> return null
            }
            child = child.nextSibling
        }
        return null
    }

    /** The element the doc comment goes above: the spec of a group member, else the whole declaration. */
    fun anchor(identifier: PsiElement): PsiElement? {
        val named = identifier.parent ?: return null
        if (named is GoFunctionDeclaration || named is GoMethodDeclaration) return named
        val spec = if (named is GoTypeSpec) named else named.parent ?: return null
        val declaration = spec.parent ?: return null
        return if (isGroup(declaration)) spec else declaration
    }
}

/** Inserts `// Name ` above the declaration (its indentation) and puts the caret after it when the file is open in the selected editor. */
class GoAddDocCommentFix(private val name: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Add doc comment"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val identifier = descriptor.psiElement ?: return
        val file = identifier.containingFile
        val anchor = GoDocFix.anchor(identifier) ?: return
        val document = GoImportEdits.document(file) ?: return
        val text = document.charsSequence
        val offset = anchor.textRange.startOffset
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val indent = text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
        val comment = "$indent// $name "
        document.insertString(lineStart, "$comment\n")
        GoImportEdits.commit(file, document)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null && editor.document === document) editor.caretModel.moveToOffset(lineStart + comment.length)
    }
}

/** Rewrites the first word of the comment to the name, or prepends the name (lowercasing a capitalised first word). */
class GoStartCommentWithNameFix(private val name: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Start comment with the declared name"

    override fun getName(): String = "Start comment with '$name'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val comment = descriptor.psiElement as? PsiComment ?: return
        if (!comment.text.startsWith("//")) return
        val file = comment.containingFile
        val document = GoImportEdits.document(file) ?: return
        val trimmed = comment.text.removePrefix("//").trimStart()
        val word = trimmed.takeWhile { !it.isWhitespace() }
        val replacement = when {
            trimmed.isEmpty() -> "// $name "
            word.equals(name, ignoreCase = true) -> "// $name${trimmed.removePrefix(word)}"
            word.length > 1 && word[0].isUpperCase() && !word[1].isUpperCase() -> "// $name ${word[0].lowercaseChar()}${trimmed.substring(1)}"
            else -> "// $name $trimmed"
        }
        document.replaceString(comment.textRange.startOffset, comment.textRange.endOffset, replacement)
        GoImportEdits.commit(file, document)
    }
}
