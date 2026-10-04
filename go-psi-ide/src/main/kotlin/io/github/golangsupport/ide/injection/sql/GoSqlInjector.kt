package io.github.golangsupport.ide.injection.sql

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.injection.GoInjectionTargets
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * Injects SQL (the platform's generic dialect; the user's dialect mapping then applies) into the query argument of `database/sql`, sqlx and pgx
 * methods ([GoSqlDetect.QUERY_ARGUMENTS]) and into a raw literal that starts with a SQL keyword and is assigned to a const / variable whose name ends
 * with `Query` / `SQL` / `Sql`. Lives in its own descriptor: the SQL language comes with the Database plugin, absent from most IDEs.
 * Gated by [GoIdeFeature.SEMANTIC_COLORS] like the other injectors (the call forms depend on resolve).
 */
class GoSqlInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(GoStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val literal = context as? GoStringLiteral ?: return
        val host = literal as? PsiLanguageInjectionHost ?: return
        if (!host.isValidHost || !GoIdeFeatureGate.enabled(GoIdeFeature.SEMANTIC_COLORS, literal.project)) return
        // by id, not SqlLanguage.INSTANCE: the plugin compiles without the Database plugin (IntelliJ IDEA Community has none)
        val sql = Language.findLanguageByID("SQL") ?: return
        if (!isSql(literal)) return
        registrar.startInjecting(sql).addPlace(null, null, host, GoInjectionTargets.contentRange(host)).doneInjecting()
    }

    private fun isSql(literal: GoStringLiteral): Boolean = isQueryArgument(literal) || isNamedQueryLiteral(literal)

    /** `db.Query("...")`: the cheap name/position check first, then the receiver type of the resolved method. */
    private fun isQueryArgument(literal: GoStringLiteral): Boolean {
        val call = (0..2).firstNotNullOfOrNull { GoInjectionTargets.callOfArgument(literal, it) } ?: return false
        val name = GoInjectionTargets.calleeName(call) ?: return false
        val indices = GoSqlDetect.argumentIndices(name)
        if (indices.isEmpty()) return false
        // the literal must be one of the positions this method name can have (the exact one depends on the resolved package)
        if (indices.none { GoInjectionTargets.callOfArgument(literal, it) === call }) return false
        val ref = GoInjectionTargets.calleeReference(call) ?: return false
        val target = GoSemanticService.getInstance(call.project).resolve(ref).singleOrNull() ?: return false
        val file = target.containingFile as? GoFile ?: return false
        val pkg = GoSemanticService.getInstance(call.project).packageOf(file)?.importPath ?: file.packageName ?: return false
        val receiver = when (target) {
            is GoMethodDeclaration -> target.receiverTypeName
            // pgx.Tx is an interface: the method is a spec of the interface type
            is GoMethodSpec -> PsiTreeUtil.getParentOfType(target, GoTypeSpec::class.java)?.name
            else -> null
        } ?: return false
        val index = GoSqlDetect.queryArgument(pkg, receiver, name) ?: return false
        return GoInjectionTargets.callOfArgument(literal, index) === call
    }

    /** `const userQuery = ` + a raw string that reads like SQL: the name says query and the content starts with a statement keyword. */
    private fun isNamedQueryLiteral(literal: GoStringLiteral): Boolean {
        if (!literal.text.startsWith("`") || !GoSqlDetect.startsLikeSql(literal.text.trim('`'))) return false
        val spec = literal.parent ?: return false
        // the expression list is a private rule: the values and the definitions are siblings under the spec (or `:=` declaration)
        val index = spec.children.filterIsInstance<GoExpression>().indexOf(literal)
        val names = spec.children.filter { it is GoConstDefinition || it is GoVarDefinition }.mapNotNull { (it as GoNamedElement).name }
        return names.getOrNull(index)?.let { GoSqlDetect.nameSaysQuery(it) } == true
    }
}

/** The pure part of the SQL detection: no PSI, no platform. */
object GoSqlDetect {
    private const val SQL = "database/sql"
    private const val SQLX = "github.com/jmoiron/sqlx"
    private const val PGX = "github.com/jackc/pgx/v5"
    private const val PGXPOOL = "github.com/jackc/pgx/v5/pgxpool"

    private val dbSql = mapOf(
        "Query" to 0, "QueryRow" to 0, "Exec" to 0, "Prepare" to 0,
        "QueryContext" to 1, "QueryRowContext" to 1, "ExecContext" to 1, "PrepareContext" to 1,
    )
    private val sqlx = mapOf(
        "Select" to 1, "Get" to 1, "Queryx" to 0, "QueryRowx" to 0, "NamedExec" to 0, "NamedQuery" to 0, "MustExec" to 0, "Rebind" to 0, "Preparex" to 0, "PrepareNamed" to 0,
        "SelectContext" to 2, "GetContext" to 2, "QueryxContext" to 1, "QueryRowxContext" to 1, "NamedExecContext" to 1, "NamedQueryContext" to 1,
        "MustExecContext" to 1, "PreparexContext" to 1, "PrepareNamedContext" to 1,
    )
    private val pgx = mapOf("Query" to 1, "QueryRow" to 1, "Exec" to 1)

    /** (package, receiver type) to (method to the index of the query argument). Methods promoted from an embedded type resolve to the embedded one. */
    private val table: Map<Pair<String, String>, Map<String, Int>> = buildMap {
        for (t in listOf("DB", "Tx", "Conn")) put(SQL to t, dbSql)
        for (t in listOf("DB", "Tx", "Conn")) put(SQLX to t, sqlx)
        put(PGX to "Conn", pgx); put(PGX to "Tx", pgx); put(PGXPOOL to "Pool", pgx); put(PGXPOOL to "Conn", pgx); put(PGXPOOL to "Tx", pgx)
    }

    private val allIndices: Map<String, Set<Int>> = table.values.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.toSet() }

    /** Every argument position the method [name] has in any known receiver: the check that runs before resolve. */
    fun argumentIndices(name: String): Set<Int> = allIndices[name].orEmpty()

    fun queryArgument(pkg: String, receiver: String, method: String): Int? = table[pkg to receiver]?.get(method)

    private val KEYWORDS = Regex("^(SELECT|INSERT|UPDATE|DELETE|WITH|CREATE|ALTER|DROP)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    fun nameSaysQuery(name: String): Boolean = name.endsWith("Query") || name.endsWith("SQL") || name.endsWith("Sql")

    fun startsLikeSql(content: String): Boolean = KEYWORDS.containsMatchIn(content.trim())
}
