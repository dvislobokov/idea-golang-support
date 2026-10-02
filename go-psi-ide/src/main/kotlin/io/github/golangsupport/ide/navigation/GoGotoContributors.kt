package io.github.golangsupport.ide.navigation

import com.intellij.lang.Language
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.stubs.index.GoAllPrivateNamesIndex
import io.github.golangsupport.lang.stubs.index.GoAllPublicNamesIndex
import io.github.golangsupport.lang.stubs.index.GoTypesIndex

/**
 * Go to Symbol/Class over stub indices: names come from the index keys, elements from stubs, so no
 * AST is loaded. Not DumbAware: stub indices are unavailable while indexing.
 */
abstract class GoGotoContributorBase<T : GoNamedElement>(
    private val keys: List<StubIndexKey<String, T>>,
    private val psiClass: Class<T>,
) : ChooseByNameContributorEx {

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val index = StubIndex.getInstance()
        for (key in keys) {
            if (!index.processAllKeys(key, processor, scope, filter)) return
        }
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val index = StubIndex.getInstance()
        for (key in keys) {
            val continued = index.processElements(
                key, name, parameters.project, parameters.searchScope, parameters.idFilter, psiClass,
            ) { element -> processor.process(element) }
            if (!continued) return
        }
    }
}

/** Package-level functions, methods, types, vars and consts, exported or not. */
class GoGotoSymbolContributor : GoGotoContributorBase<GoNamedElement>(
    listOf(GoAllPublicNamesIndex.KEY, GoAllPrivateNamesIndex.KEY),
    GoNamedElement::class.java,
)

/** Package-level type specs, aliases included; qualified names are `pkg.T`. */
class GoGotoClassContributor : GoGotoContributorBase<GoTypeSpec>(
    listOf(GoTypesIndex.KEY),
    GoTypeSpec::class.java,
), GotoClassContributor {
    override fun getQualifiedName(item: NavigationItem): String? {
        val spec = item as? GoTypeSpec ?: return null
        val name = spec.name ?: return null
        val pkg = (spec.containingFile as? GoFile)?.packageName
        return if (pkg.isNullOrEmpty()) name else "$pkg.$name"
    }

    override fun getQualifiedNameSeparator(): String = "."

    override fun getElementKind(): String = "type"

    override fun getElementLanguage(): Language = GoLanguage
}
