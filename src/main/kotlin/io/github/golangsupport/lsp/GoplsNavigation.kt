package io.github.golangsupport.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.GoDeclarationPsi
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lint.GoSignatureProvider
import io.github.golangsupport.lint.GoSignatures
import com.intellij.openapi.util.TextRange
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * Requests to the gopls the platform has started, for what its LSP client does not do (checked on 2026.1): it has no
 * `textDocument/implementation` at all, shows no link under Ctrl + mouse, and its Find Usages does not start from a declaration that
 * is a PSI element of ours. The connection and the document synchronization stay the ones of the platform.
 */
object Gopls {
    private val LOG = logger<Gopls>()

    /** A place in a file, as offsets: what is left of a `Location` once the file is found and its lines are known. */
    class Place(val file: VirtualFile, val startOffset: Int, val endOffset: Int)

    fun client(project: Project): LspClient? =
        LspClientManager.getInstance(project).getClients(GoplsIntegrationProvider::class.java).firstOrNull { it.state == LspServerState.Running }

    /** Columns of the protocol are UTF-16 code units, as the offsets of a Java string are. */
    fun position(document: Document, offset: Int): Position {
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        return Position(line, offset - document.getLineStartOffset(line))
    }

    fun definition(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): List<Place> =
        locations(client, request(client, timeoutMs) { it.textDocumentService.definition(DefinitionParams(client.getDocumentIdentifier(file), position)) })

    fun implementations(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): List<Place> =
        locations(client, request(client, timeoutMs) { it.textDocumentService.implementation(ImplementationParams(client.getDocumentIdentifier(file), position)) })

    fun typeDefinition(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): List<Place> =
        locations(client, request(client, timeoutMs) { it.textDocumentService.typeDefinition(TypeDefinitionParams(client.getDocumentIdentifier(file), position)) })

    fun signatureHelp(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): SignatureHelp? =
        request(client, timeoutMs) { it.textDocumentService.signatureHelp(SignatureHelpParams(client.getDocumentIdentifier(file), position)) }

    /** The ranges of the file to highlight for the caret at [position], with whether each is a write; ranges as offsets of [document]. */
    fun documentHighlights(client: LspClient, file: VirtualFile, document: Document, position: Position, timeoutMs: Int): List<Pair<TextRange, Boolean>> {
        val params = DocumentHighlightParams(client.getDocumentIdentifier(file), position)
        return request(client, timeoutMs) { it.textDocumentService.documentHighlight(params) }.orEmpty().mapNotNull { highlight ->
            val range = highlight.range
            if (range.start.line >= document.lineCount || range.end.line >= document.lineCount) return@mapNotNull null
            val start = document.getLineStartOffset(range.start.line) + range.start.character
            val end = document.getLineStartOffset(range.end.line) + range.end.character
            if (start < 0 || end > document.textLength || start >= end) null else TextRange(start, end) to (highlight.kind == DocumentHighlightKind.Write)
        }
    }

    fun references(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): List<Place> {
        val params = ReferenceParams(client.getDocumentIdentifier(file), position, ReferenceContext(false))
        return request(client, timeoutMs) { it.textDocumentService.references(params) }.orEmpty().mapNotNull { place(client, it.uri, it.range.start, it.range.end) }
    }

    /**
     * The answer, or nothing: a server that is busy, gone or answers with an error must not break navigation of the IDE. Locations are
     * turned into places by the caller, on its own thread: a read action started elsewhere while this one waits would wait for a pending write.
     */
    private fun <T> request(client: LspClient, timeoutMs: Int, call: (LanguageServer) -> CompletableFuture<T>): T? = try {
        client.sendRequestSync(timeoutMs, call)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.info("gopls request has failed: $e")
        null
    }

    private fun locations(client: LspClient, answer: Either<out List<Location>, out List<LocationLink>>?): List<Place> = when {
        answer == null -> emptyList()
        answer.isLeft -> answer.left.orEmpty().mapNotNull { place(client, it.uri, it.range.start, it.range.end) }
        else -> answer.right.orEmpty().mapNotNull { place(client, it.targetUri, it.targetSelectionRange.start, it.targetSelectionRange.end) }
    }

    private fun place(client: LspClient, uri: String, start: Position, end: Position): Place? = ReadAction.compute<Place?, RuntimeException> {
        val file = client.descriptor.findFileByUri(uri) ?: return@compute null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
        fun offset(position: Position): Int {
            if (position.line >= document.lineCount) return document.textLength
            return (document.getLineStartOffset(position.line) + position.character).coerceAtMost(document.getLineEndOffset(position.line))
        }
        Place(file, offset(start), offset(end))
    }

    /** The code actions for a range, of every kind: a bare command is wrapped into an action, so that the caller has one type to deal with. */
    fun codeActions(client: LspClient, params: CodeActionParams, timeoutMs: Int): List<CodeAction> =
        request(client, timeoutMs) { it.textDocumentService.codeAction(params) }.orEmpty().mapNotNull { either ->
            if (either.isRight) either.right else either.left?.let { command -> CodeAction(command.title).apply { this.command = command } }
        }

    /** The text gopls shows on hover: a signature or a declaration in a code block, then the documentation. */
    fun hover(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): String? {
        val hover = request(client, timeoutMs) { it.textDocumentService.hover(HoverParams(client.getDocumentIdentifier(file), position)) } ?: return null
        val contents = hover.contents ?: return null
        return if (contents.isRight) contents.right?.value else contents.left?.joinToString("\n") { if (it.isLeft) it.left else it.right?.value.orEmpty() }
    }

    /** The PSI element of a place: the declaration, when the place is the name of one (it presents itself better), otherwise the token. */
    fun element(project: Project, place: Place): PsiElement? {
        val leaf = PsiManager.getInstance(project).findFile(place.file)?.findElementAt(place.startOffset) ?: return null
        return GoDeclarationPsi.ofName(leaf) ?: leaf
    }
}

/** What a function returns, for the fixes of the linter: read from the signature of its hover. */
class GoplsSignatureProvider : GoSignatureProvider {
    override fun resultCount(project: Project, file: VirtualFile, offset: Int): Int? {
        val client = Gopls.client(project) ?: return null
        val position = ReadAction.compute<Position?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file)?.let { Gopls.position(it, offset) } } ?: return null
        return Gopls.hover(client, file, position, 5000)?.let(GoSignatures::inHover)?.let(GoSignatures::resultCount)
    }
}

/**
 * Go to Declaration through PSI elements instead of the symbols of the platform LSP client (its own go-to-definition is switched off
 * in [GoplsDescriptor], or every target would be offered twice). With PSI targets the platform underlines the name under
 * Ctrl + mouse and shows what it leads to: the navigation of its LSP client does neither (seen live).
 */
class GoplsGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val element = sourceElement?.takeIf { it.containingFile is GoFile && it.node?.elementType == GoTypes.IDENTIFIER } ?: return null
        val project = element.project
        if (GoFeatures.native(GoFeature.NAVIGATION, project)) return null
        val file = element.containingFile.virtualFile ?: return null
        val client = Gopls.client(project) ?: return null
        val start = element.textRange.startOffset
        // the document of the file of the element, not of the editor: they differ when the element comes from a popup or an injected editor
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val places = Gopls.definition(client, file, Gopls.position(document, start), TIMEOUT_MS)
        // the name of a declaration leads to itself: nothing to go to, the platform offers the usages then
        val targets = places.filter { !(it.file == file && it.startOffset == start) }.mapNotNull { Gopls.element(project, it) }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private companion object {
        // asked while the mouse moves: better no link than a frozen read action
        const val TIMEOUT_MS = 1500
    }
}

/** Find Usages and Show Usages of a declaration: `textDocument/references` of gopls, as usages of the IDE. */
class GoplsUsageSearcher : CustomUsageSearcher() {
    override fun processElementUsages(element: PsiElement, processor: Processor<in Usage>, options: FindUsagesOptions) {
        // called on a background thread without a read action (seen live): everything about the element is read in one
        val (project, file, position) = ReadAction.compute<Triple<Project, VirtualFile, Position>?, RuntimeException> {
            // a local comes as its PSI definition (the Find Usages provider of the PSI takes named elements) or as a bare token
            val accepted = GoDeclarationPsi.declaration(element) != null || GoDeclarationPsi.isLocalName(element) ||
                element is GoNamedElement && element.nameIdentifier?.let(GoDeclarationPsi::isLocalName) == true
            if (accepted) GoplsTargets.of(element) else null
        } ?: return
        if (GoFeatures.native(GoFeature.USAGES, project)) return
        val client = Gopls.client(project) ?: return
        for (place in Gopls.references(client, file, position, TIMEOUT_MS)) {
            val usage = ReadAction.compute<Usage?, RuntimeException> {
                PsiManager.getInstance(project).findFile(place.file)?.let { UsageInfo2UsageAdapter(UsageInfo(it, place.startOffset, place.endOffset)) }
            } ?: continue
            if (!processor.process(usage)) return
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

/**
 * With gopls on duty for usages, Find Usages lists its usages alone: the handler runs no reference search, so the references of the
 * PSI (which resolve whatever the switch says) do not double every site (seen live since step 4). The custom usage searcher above still
 * runs: the platform calls every one of them besides the handler. Highlighting of the identifier at the caret keeps the references of
 * the PSI ([FindUsagesHandler.findReferencesToHighlight] is not touched). Stands down when the PSI is the source.
 */
class GoplsFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean =
        element is GoNamedElement && element.containingFile is GoFile && !GoFeatures.native(GoFeature.USAGES, element.project)

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler = object : FindUsagesHandler(element) {
        override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean = true
    }
}

/** Go to Implementation (Ctrl+Alt+B) of an interface, of its method, or of a type: `textDocument/implementation`, which the platform client lacks. */
class GoplsImplementationSearch : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>() {
    override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
        if (GoFeatures.native(GoFeature.NAVIGATION, parameters.project)) return
        val declaration = ReadAction.compute<GoNamedElement?, RuntimeException> {
            // gopls answers a question about a plain function, a field or a value with an error, which the platform logs as a warning
            GoDeclarationPsi.declaration(parameters.element)?.takeIf { GoDeclarationPsi.kindOf(it) in WITH_IMPLEMENTATIONS }
        } ?: return
        val (project, file, position) = ReadAction.compute<Triple<Project, VirtualFile, Position>?, RuntimeException> { GoplsTargets.of(declaration) } ?: return
        val client = Gopls.client(project) ?: return
        for (place in Gopls.implementations(client, file, position, TIMEOUT_MS)) {
            val target = ReadAction.compute<PsiElement?, RuntimeException> { Gopls.element(project, place) } ?: continue
            if (!consumer.process(target)) return
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        val WITH_IMPLEMENTATIONS = setOf(GoDeclarationKind.INTERFACE, GoDeclarationKind.INTERFACE_METHOD, GoDeclarationKind.STRUCT, GoDeclarationKind.TYPE, GoDeclarationKind.METHOD)
    }
}

object GoplsTargets {
    /** The project, the file and the position of the name of a named element (or of a bare identifier): what a request about it is made with. Needs read access. */
    fun of(element: PsiElement): Triple<Project, VirtualFile, Position>? {
        if (!element.isValid) return null
        val file = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return Triple(element.project, file, Gopls.position(document, GoDeclarationPsi.nameOffset(element)))
    }
}
