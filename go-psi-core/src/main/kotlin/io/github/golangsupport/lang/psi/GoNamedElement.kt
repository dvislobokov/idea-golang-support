package io.github.golangsupport.lang.psi

import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiNameIdentifierOwner

/**
 * A Go declaration with a name: functions, methods, type specs, vars, consts, parameters, fields,
 * type parameters, labels, interface methods, imports and the package clause. For stub-based
 * elements [getName] is answered from the stub without loading the AST.
 */
interface GoNamedElement : GoCompositeElement, PsiNameIdentifierOwner, NavigatablePsiElement {
    /** True if the name is exported (starts with a Unicode upper-case letter). */
    fun isPublic(): Boolean

    /**
     * The first comment of the doc comment run bound into this declaration (the comments directly above
     * it, without a blank line in between; see `DOC_COMMENT_BINDER`), or `null`.
     *
     * For specs of a grouped declaration (`var (...)`, `const (...)`, `type (...)`) the spec's own doc is
     * used, falling back to the doc of the group, as `go/doc` does. `var`/`const` definitions use the doc of
     * their spec, fields the doc of their field declaration. Parameters, type parameters, receivers and
     * labels have none. Reads the AST of the file (no stub support).
     */
    val docComment: PsiComment?

    /**
     * The doc comment as `ast.CommentGroup.Text` renders it: comment markers removed, directive lines
     * (`//go:generate`) dropped, trailing spaces trimmed, one trailing newline. `null` if there is no doc.
     */
    val docText: String?
}
