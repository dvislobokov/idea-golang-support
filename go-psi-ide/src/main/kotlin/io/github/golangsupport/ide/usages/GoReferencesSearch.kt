package io.github.golangsupport.ide.usages

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.scope.GoScopes

/**
 * Reference search for imports whose local name differs from the import spec's name: the spec is
 * named after the last path segment (`yaml.v3` for `gopkg.in/yaml.v3`), while qualifiers use the
 * package name (`yaml.Marshal`), so the platform's word search for the spec name finds nothing.
 * Imports are file-local, so this walks the containing file only. Everything else (locals,
 * package-level names, fields, methods, types, labels, imports named by their package) is served
 * by the default word-index search restricted by `getUseScope`.
 */
class GoReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val spec = parameters.elementToSearch as? GoImportSpec ?: return
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.USAGES, spec.project)) return
        if (spec.isDot || spec.isBlank) return
        val localName = GoScopes.importName(spec)
        if (localName == spec.name) return
        val file = spec.containingFile ?: return
        if (!parameters.effectiveSearchScope.contains(file.virtualFile ?: return)) return
        for (ref in PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java)) {
            if (ref.expression != null || ref.identifier.text != localName) continue
            val reference = ref.reference ?: continue
            if (reference.isReferenceTo(spec) && !consumer.process(reference)) return
        }
    }
}
