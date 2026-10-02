package io.github.golangsupport.lang.psi

/** Stub-backed extra API of [GoImportSpec]. */
interface GoImportSpecBase {
    /** The unquoted import path (`"fmt"` -> `fmt`). */
    val path: String

    /** The explicit local name: an identifier, `.` or `_`; `null` when absent. */
    val alias: String?

    /** `import . "p"` */
    val isDot: Boolean

    /** `import _ "p"` */
    val isBlank: Boolean
}

/** Stub-backed extra API of [GoTypeSpec]. */
interface GoTypeSpecBase {
    /** `type A = B` */
    val isAlias: Boolean
}

/** Stub-backed extra API of [GoMethodDeclaration]. */
interface GoMethodDeclarationBase {
    /** The receiver's base type name (`T` for `(r *T)`, `(T)`, `(r *T[K, V])`), `null` if unparsable. */
    val receiverTypeName: String?

    /** True for pointer receivers (`*T`). */
    val isPointerReceiver: Boolean
}
