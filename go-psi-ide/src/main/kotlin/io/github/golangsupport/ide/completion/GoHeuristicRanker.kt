package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoTypes
import kotlin.math.ln
import org.jetbrains.annotations.TestOnly
import kotlin.math.min

/**
 * The `completionRanker` without a model (PLAN.md, heuristic ranker): the deterministic order of [GoCompletionWeigher] — expected-type
 * match, then the scope level — kept as the leading part of the score, and within one bucket the names the code uses: how often the name
 * occurs in the file and in its package (identifier tokens of the lexer, cached per file on its modification stamp), and how recently it
 * was chosen from a list in this project ([GoCompletionRecency]). Registered `order="last"`: the ML ranker, when it is in the build and
 * answers, wins. No marker.
 */
class GoHeuristicRanker : GoCompletionRanker {
    override fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double>? {
        // never abstains, even for one candidate: a batch left unscored would sort below every scored one (GoCompletionWeigher's buckets)
        if (candidates.isEmpty()) return emptyList()
        val file = GoIdentifierCounts.ofFile(context.file)
        val pack = GoIdentifierCounts.ofPackage(context.file)
        val recency = GoCompletionRecency.getInstance(context.file.project)
        return candidates.map { c ->
            val name = c.lookupString.substringAfterLast('.')
            // the name being typed is in the file once already: not a use
            val inFile = (file[name] ?: 0) - (if (name == context.prefix) 1 else 0)
            val bonus = min(MAX_FREQUENCY, ln(1.0 + 2 * inFile.coerceAtLeast(0) + (pack[name] ?: 0))) + MAX_RECENCY * recency.weight(c.lookupString)
            score(c.expectedTypeMatch, c.scopeLevel, bonus)
        }
    }

    companion object {
        const val MAX_FREQUENCY = 4.0
        const val MAX_RECENCY = 5.0

        /** The expected-type match first, the scope level next (lower is closer), the [bonus] (< 10) only between equals of both. */
        fun score(expectedMatch: Int, scopeLevel: Int, bonus: Double): Double = expectedMatch * 10_000.0 + (100 - scopeLevel.coerceIn(0, 99)) * 10.0 + bonus.coerceIn(0.0, 9.99)
    }
}

/** How often each identifier occurs in a Go file, counted over the tokens of the lexer; for a package, the sum over its files in one directory. */
object GoIdentifierCounts {
    private val FILE = Key.create<Pair<Long, Map<String, Int>>>("go.completion.identifierCounts")
    private const val MAX_FILES = 50
    private const val MAX_FILE_CHARS = 300_000

    fun ofFile(file: PsiFile): Map<String, Int> {
        val stamp = file.modificationStamp
        file.getUserData(FILE)?.takeIf { it.first == stamp }?.let { return it.second }
        val counts = count(file.viewProvider.contents)
        file.putUserData(FILE, stamp to counts)
        return counts
    }

    /** The other Go files of the directory of [file] (the package, test files included), each cached on its own stamp; no AST is loaded. */
    fun ofPackage(file: PsiFile): Map<String, Int> {
        val directory = file.originalFile.containingDirectory ?: return emptyMap()
        val result = HashMap<String, Int>()
        var files = 0
        for (sibling in directory.files) {
            if (sibling == file.originalFile || sibling.fileType != GoFileType || files++ >= MAX_FILES) continue
            for ((name, n) in ofFile(sibling)) result.merge(name, n, Int::plus)
        }
        return result
    }

    fun count(text: CharSequence): Map<String, Int> {
        if (text.length > MAX_FILE_CHARS) return emptyMap()
        val result = HashMap<String, Int>()
        val lexer = GoLexer()
        lexer.start(text)
        while (lexer.tokenType != null) {
            if (lexer.tokenType == GoTypes.IDENTIFIER) result.merge(text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), 1, Int::plus)
            lexer.advance()
        }
        return result
    }
}

/** The lookup strings last chosen from a Go completion list in this project, newest first; in memory only. */
@Service(Service.Level.PROJECT)
class GoCompletionRecency {
    private val chosen = LinkedHashMap<String, Unit>()

    @Synchronized
    fun record(lookupString: String) {
        chosen.remove(lookupString)
        chosen[lookupString] = Unit
        if (chosen.size > SIZE) chosen.remove(chosen.keys.first())
    }

    /** 1.0 for the last one chosen, falling to ~0 for the oldest kept; 0.0 for one not chosen lately. */
    @Synchronized
    fun weight(lookupString: String): Double {
        if (lookupString !in chosen.keys) return 0.0
        val age = chosen.size - 1 - chosen.keys.indexOf(lookupString)
        return 1.0 - age.toDouble() / SIZE
    }

    @Synchronized
    fun clear() = chosen.clear()

    companion object {
        const val SIZE = 100

        fun getInstance(project: Project): GoCompletionRecency = project.service()
    }
}

/** Feeds [GoCompletionRecency] with the Go items chosen from a list (a project listener of the lookup manager). */
class GoCompletionRecencyListener(private val project: Project) : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        newLookup?.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val item = event.item ?: return
                if (!recording()) return
                if (GoCompletionWeigher.infoOf(item) != null && !project.isDisposed) GoCompletionRecency.getInstance(project).record(item.lookupString)
            }
        })
    }

    companion object {
        /** Tests share one light project: a choice made by one test would reorder the lists of the next, so tests record only when they ask. */
        @Volatile
        @TestOnly
        var recordInTests = false

        private fun recording(): Boolean = !ApplicationManager.getApplication().isUnitTestMode || recordInTests
    }
}
