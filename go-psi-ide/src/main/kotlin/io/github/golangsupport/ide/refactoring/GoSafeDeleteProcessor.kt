package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegateBase
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements

/**
 * Safe Delete (Alt+Delete) of package-level functions, methods, types, variables and constants, struct fields and interface
 * methods. Usages come from [ReferencesSearch]; any usage left outside the deleted elements is unsafe, so the platform shows its
 * conflicts dialog. More conflicts: a method that implements a project interface method called through the interface, a field
 * listed by an unkeyed composite literal, a constant whose expression the next constants of its group repeat (`iota`). The text
 * goes with its doc comment and its lines; a name of `a, b T` / `var a, b = 1, 2` goes alone, with its value.
 */
class GoSafeDeleteProcessor : SafeDeleteProcessorDelegateBase() {

    override fun handlesElement(element: PsiElement): Boolean = isSupported(element) && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)

    override fun getElementsToSearch(element: PsiElement, module: Module?, allElementsToDelete: Collection<PsiElement>): Collection<PsiElement> = listOf(element)

    override fun findUsages(element: PsiElement, allElementsToDelete: Array<out PsiElement>, result: MutableList<in UsageInfo>): NonCodeUsageSearchInfo {
        ReferencesSearch.search(element).forEach { ref ->
            val e = ref.element
            if (!inside(e, allElementsToDelete)) result.add(SafeDeleteReferenceSimpleDeleteUsageInfo(e, element, false))
            true
        }
        return NonCodeUsageSearchInfo({ e -> inside(e, allElementsToDelete) }, element)
    }

    override fun getAdditionalElementsToDelete(element: PsiElement, allElementsToDelete: Collection<PsiElement>, askUser: Boolean): Collection<PsiElement>? = null

    override fun findConflicts(element: PsiElement, allElementsToDelete: Array<out PsiElement>, usages: Array<out UsageInfo>, conflicts: MultiMap<PsiElement, String>) {
        when (element) {
            is GoMethodDeclaration -> interfaceCalls(element, allElementsToDelete, conflicts)
            is GoFieldDefinition -> unkeyedLiterals(element, conflicts)
            is GoConstDefinition -> repeatedByNext(element, conflicts)
            is GoVarDefinition -> (element.parent as? GoVarSpec)?.let { spec ->
                if (spec.varDefinitionList.size > 1 && spec.expressionList.isNotEmpty() && spec.expressionList.size != spec.varDefinitionList.size) {
                    conflicts.putValue(element, "Variable ${element.name} is initialized together with the others by one call; only its name is removed")
                }
            }
        }
    }

    /** Calls through the project interfaces whose method [method] implements: without the method the type no longer implements them. */
    private fun interfaceCalls(method: GoMethodDeclaration, all: Array<out PsiElement>, conflicts: MultiMap<PsiElement, String>) {
        val scope = GlobalSearchScope.projectScope(method.project)
        val owner = GoImplementations.receiverTypeSpec(method)?.name ?: method.receiverTypeName ?: "?"
        for (spec in GoImplementations.superMethods(method, scope)) {
            if (inside(spec, all)) continue
            val iface = GoImplementations.interfaceSpecOf(spec)?.name ?: "interface"
            ReferencesSearch.search(spec, scope).forEach { ref ->
                if (!inside(ref.element, all)) {
                    conflicts.putValue(ref.element, "Method $owner.${method.name} implements $iface.${spec.name}, which is called through the interface here")
                }
                true
            }
        }
    }

    /** `T{1, "x"}` lists every field of `T` by position: removing one breaks it. */
    private fun unkeyedLiterals(field: GoFieldDefinition, conflicts: MultiMap<PsiElement, String>) {
        val struct = PsiTreeUtil.getParentOfType(field, GoStructType::class.java) ?: return
        val typeSpec = PsiTreeUtil.getParentOfType(struct, GoTypeSpec::class.java, true, GoStructType::class.java) ?: return
        ReferencesSearch.search(typeSpec).forEach { ref ->
            val literal = ref.element.parent as? GoCompositeLit
            val elements = literal?.literalValue?.elements.orEmpty()
            if (elements.isNotEmpty() && elements.all { it.key == null }) {
                conflicts.putValue(literal, "The unkeyed literal of ${typeSpec.name} lists field ${field.name} by position")
            }
            true
        }
    }

    /** `const ( A = iota; B; C )`: the constants after a spec repeat its expression, and with `iota` their values shift. */
    private fun repeatedByNext(const: GoConstDefinition, conflicts: MultiMap<PsiElement, String>) {
        val spec = const.parent as? GoConstSpec ?: return
        val group = (spec.parent as? GoConstDeclaration)?.constSpecList ?: return
        val next = group.getOrNull(group.indexOf(spec) + 1) ?: return
        if (spec.constDefinitionList.size > 1) return
        when {
            spec.expressionList.isNotEmpty() && next.expressionList.isEmpty() ->
                conflicts.putValue(const, "The constants after ${const.name} repeat its expression; they would lose their values")
            group.any { s -> s.text.contains("iota") } ->
                conflicts.putValue(const, "Removing ${const.name} shifts the iota values of the constants after it")
        }
    }

    override fun preprocessUsages(project: Project, usages: Array<out UsageInfo>): Array<UsageInfo> = arrayOf(*usages)

    /**
     * Removes what goes with the element (its declaration keyword or group, the commas, a value, the doc comment's line, a blank
     * line) on the PSI and leaves the element itself to the platform's delete that follows. Editing the document instead would
     * reparse the file, and the platform's smart pointer to the deleted element would come back as its neighbour (`b` of
     * `var a, b`) and delete that.
     */
    override fun prepareForDeletion(element: PsiElement) {
        val file = element.containingFile ?: return
        val own = element.textRange
        val extras = deletionRanges(element, file.viewProvider.contents).flatMap { r ->
            if (!r.contains(own)) listOf(r) else listOf(TextRange(r.startOffset, own.startOffset), TextRange(own.endOffset, r.endOffset))
        }.filter { !it.isEmpty }
        for (r in extras.sortedByDescending { it.startOffset }) removeText(file, r)
    }

    /** Removes [range] of [file] leaf by leaf; a whitespace or comment leaf it cuts keeps the rest of its text. */
    private fun removeText(file: PsiFile, range: TextRange) {
        val leaves = ArrayList<PsiElement>()
        var leaf = file.findElementAt(range.startOffset)
        while (leaf != null && leaf.textRange.startOffset < range.endOffset) {
            if (leaf.textLength > 0) leaves += leaf
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        for (l in leaves.asReversed()) {
            val r = l.textRange
            if (range.contains(r)) {
                l.node.treeParent?.removeChild(l.node)
            } else {
                val cut = r.intersection(range) ?: continue
                val text = l.text
                val rest = text.substring(0, cut.startOffset - r.startOffset) + text.substring(cut.endOffset - r.startOffset)
                (l as? LeafPsiElement)?.replaceWithText(rest)
            }
        }
    }

    override fun isToSearchInComments(element: PsiElement?): Boolean = RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_COMMENTS

    override fun setToSearchInComments(element: PsiElement?, enabled: Boolean) {
        RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_COMMENTS = enabled
    }

    override fun isToSearchForTextOccurrences(element: PsiElement?): Boolean = RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_NON_JAVA

    override fun setToSearchForTextOccurrences(element: PsiElement?, enabled: Boolean) {
        RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_NON_JAVA = enabled
    }

    companion object {
        /** The declarations Safe Delete serves: package-level ones, struct fields and interface methods (not locals nor parameters). */
        fun isSupported(element: PsiElement): Boolean = when (element) {
            is GoFunctionDeclaration, is GoMethodDeclaration, is GoFieldDefinition, is GoMethodSpec -> true
            is GoTypeSpec, is GoVarDefinition, is GoConstDefinition -> GoPsiUtil.functionOwner(element) == null
            else -> false
        }

        private fun inside(e: PsiElement, all: Array<out PsiElement>): Boolean = all.any { PsiTreeUtil.isAncestor(it, e, false) }

        /** The text to remove for [element] in [text]. */
        fun deletionRanges(element: PsiElement, text: CharSequence): List<TextRange> = when (element) {
            is GoTypeSpec -> listOf(lines(text, specOrDeclaration(element, (element.parent as? GoTypeDeclaration)?.typeSpecList)))
            is GoVarDefinition -> {
                val spec = element.parent as GoVarSpec
                if (spec.varDefinitionList.size == 1) listOf(lines(text, specOrDeclaration(spec, (spec.parent as? GoVarDeclaration)?.varSpecList)))
                else listItem(element, spec.varDefinitionList, spec.expressionList)
            }
            is GoConstDefinition -> {
                val spec = element.parent as GoConstSpec
                if (spec.constDefinitionList.size == 1) listOf(lines(text, specOrDeclaration(spec, (spec.parent as? GoConstDeclaration)?.constSpecList)))
                else listItem(element, spec.constDefinitionList, spec.expressionList)
            }
            is GoFieldDefinition -> {
                val declaration = element.parent as GoFieldDeclaration
                if (declaration.fieldDefinitionList.size == 1) listOf(lines(text, declaration.textRange))
                else listItem(element, declaration.fieldDefinitionList, emptyList())
            }
            else -> listOf(lines(text, element.textRange))
        }

        /** A spec alone in its declaration takes the declaration (`type T int`, `var x = 1`) with it. */
        private fun specOrDeclaration(spec: PsiElement, group: List<PsiElement>?): TextRange =
            if (group != null && group.size == 1) spec.parent.textRange else spec.textRange

        /** `b` of `a, b, c` with the comma before it (after it for the first), and the value at the same position when each name has one. */
        private fun listItem(item: PsiElement, items: List<PsiElement>, values: List<PsiElement>): List<TextRange> {
            fun range(list: List<PsiElement>, i: Int): TextRange =
                if (i + 1 < list.size) TextRange(list[i].textRange.startOffset, list[i + 1].textRange.startOffset)
                else TextRange(list[i - 1].textRange.endOffset, list[i].textRange.endOffset)
            val i = items.indexOf(item)
            return listOfNotNull(range(items, i), if (values.size == items.size) range(values, i) else null)
        }

        /**
         * [range] grown to whole lines when it stands alone on them (a trailing `// comment` goes too), and by one blank line when it
         * stood between two (or at the end of the file after one), so that no double blank line is left.
         */
        fun lines(text: CharSequence, range: TextRange): TextRange {
            var start = range.startOffset
            var end = range.endOffset
            var lineStart = start
            while (lineStart > 0 && (text[lineStart - 1] == ' ' || text[lineStart - 1] == '\t')) lineStart--
            if (lineStart != 0 && text[lineStart - 1] != '\n') return range
            var lineEnd = end
            while (lineEnd < text.length && (text[lineEnd] == ' ' || text[lineEnd] == '\t' || text[lineEnd] == ';')) lineEnd++
            if (lineEnd + 1 < text.length && text[lineEnd] == '/' && text[lineEnd + 1] == '/') while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            if (lineEnd < text.length && text[lineEnd] != '\n') return range
            start = lineStart
            end = minOf(lineEnd + 1, text.length)
            val blankBefore = start >= 2 && text[start - 1] == '\n' && text[start - 2] == '\n'
            if (blankBefore && end < text.length && text[end] == '\n') end++
            else if (end >= text.length) while (start >= 2 && text[start - 1] == '\n' && text[start - 2] == '\n') start--
            return TextRange(start, end)
        }
    }
}
