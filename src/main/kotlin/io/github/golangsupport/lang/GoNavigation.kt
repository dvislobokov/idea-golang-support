package io.github.golangsupport.lang

import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Names of the declarations of every Go file of the project, for Go to Class and Go to Symbol when gopls is not there (with it the
 * platform adds `workspace/symbol` results of its own). The key carries the kind (`T:Server`, `I:Handler`, `M:Start`), so that one
 * index serves both, and the files that declare an interface are known without a scan (Implement Interface).
 */
class GoDeclarationIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = VERSION
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(GoFileType)

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        GoDeclarations.scan(content.contentAsText).all().associate { key(it.kind, it.name) to null }
    }

    companion object {
        val NAME: ID<String, Void> = ID.create("golang.declarations")

        // bump when GoDeclarations starts to see declarations differently
        private const val VERSION = 2
        const val TYPE_PREFIX = "T:"
        const val INTERFACE_PREFIX = "I:"
        const val MEMBER_PREFIX = "M:"

        fun key(kind: GoDeclarationKind, name: String): String = when {
            kind == GoDeclarationKind.INTERFACE -> INTERFACE_PREFIX
            kind.isType -> TYPE_PREFIX
            else -> MEMBER_PREFIX
        } + name

        fun isTypeKey(key: String): Boolean = key.startsWith(TYPE_PREFIX) || key.startsWith(INTERFACE_PREFIX)

        /** The Go files of [scope] that declare an interface: what Implement Interface reads. Needs the index (smart mode). */
        fun filesWithInterfaces(project: Project, scope: GlobalSearchScope): Set<VirtualFile> {
            val index = FileBasedIndex.getInstance()
            val keys = ArrayList<String>()
            index.processAllKeys(NAME, { key -> if (key.startsWith(INTERFACE_PREFIX)) keys.add(key); true }, scope, null)
            return keys.flatMapTo(LinkedHashSet()) { index.getContainingFiles(NAME, it, scope) }
        }
    }
}

abstract class GoGotoContributor(private val types: Boolean, private val members: Boolean) : ChooseByNameContributorEx, DumbAware {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        FileBasedIndex.getInstance().processAllKeys(GoDeclarationIndex.NAME, { key ->
            val isType = GoDeclarationIndex.isTypeKey(key)
            if (if (isType) types else members) processor.process(key.substring(GoDeclarationIndex.TYPE_PREFIX.length)) else true
        }, scope, filter)
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val index = FileBasedIndex.getInstance()
        val keys = listOfNotNull(
            GoDeclarationIndex.key(GoDeclarationKind.STRUCT, name).takeIf { types }, GoDeclarationIndex.key(GoDeclarationKind.INTERFACE, name).takeIf { types },
            GoDeclarationIndex.key(GoDeclarationKind.FUNCTION, name).takeIf { members },
        )
        val files = keys.flatMapTo(LinkedHashSet()) { index.getContainingFiles(GoDeclarationIndex.NAME, it, parameters.searchScope) }
        val psiManager = PsiManager.getInstance(parameters.project)
        for (file in files) {
            val psiFile = psiManager.findFile(file) ?: continue
            for (declaration in PsiTreeUtil.findChildrenOfType(psiFile, GoDeclaration::class.java)) {
                val matches = declaration.name == name && (if (declaration.kind.isType) types else members)
                if (matches && !processor.process(declaration)) return
            }
        }
    }
}

/**
 * Lets Find Usages and Show Usages start from a declaration. The usages themselves come from gopls (the searcher is in the module with
 * the language server); without it there are none, and the IDE says so instead of refusing the action.
 */
/**
 * Find Usages starts from a declaration of the file structure, or from the identifier that declares a local variable, a parameter or a
 * field of a struct literal (a bare token: the scanner does not look inside functions, and the language server tells where the name is declared).
 */
class GoFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner? = null
    override fun canFindUsagesFor(element: PsiElement): Boolean = element is GoDeclaration || isLocalName(element)
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement): String = (element as? GoDeclaration)?.kind?.title ?: if (isLocalName(element)) "variable" else ""
    override fun getDescriptiveName(element: PsiElement): String =
        (element as? GoDeclaration)?.let { listOf(it.containerName, it.name.orEmpty()).filter(String::isNotEmpty).joinToString(".") } ?: element.text
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = (element as? GoDeclaration)?.info?.presentation ?: element.text.take(40)

    companion object {
        /**
         * An identifier token of a Go file that is not the name of a scanned declaration: a local, a parameter, a receiver. The tokens of a
         * function body are children of the node of the function, so the parent being a declaration says nothing; its name does.
         */
        fun isLocalName(element: PsiElement): Boolean =
            element.containingFile is GoFile && element.node?.elementType == GoTextTokens.IDENTIFIER && (element.parent as? GoDeclaration)?.nameIdentifier != element
    }
}

/** Go to Class: structs, interfaces and the other named types. */
class GoGotoClassContributor : GoGotoContributor(types = true, members = false)

/** Go to Symbol: the types, functions, methods, fields, constants and variables. */
class GoGotoSymbolContributor : GoGotoContributor(types = true, members = true)
