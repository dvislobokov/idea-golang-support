package io.github.golangsupport.semantic.api

import com.intellij.openapi.util.TextRange

/**
 * A type-checker diagnostic (`go/types` wording). [range] is the offending element in the file;
 * [code] is a stable class identifier used by inspections and the corpus gates
 * (e.g. `undefined`, `unused-import`, `unused-variable`, `assignability`, `call-arity`).
 */
data class GoDiagnostic @JvmOverloads constructor(val range: TextRange, val message: String, val code: String, val severity: Severity = Severity.ERROR) {
    enum class Severity { ERROR, WARNING }
}
