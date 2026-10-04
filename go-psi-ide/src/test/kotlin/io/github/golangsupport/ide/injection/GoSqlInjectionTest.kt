package io.github.golangsupport.ide.injection

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.lang.Language
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.injection.sql.GoSqlDetect
import java.io.File

/** SQL injection (Database plugin) into database/sql, sqlx and pgx query arguments and into named query constants. */
class GoSqlInjectionTest : GoSemanticIdeTestBase() {
    // an IDE without the Database plugin (IntelliJ IDEA Community) has no SQL to inject
    override fun shouldRunTest(): Boolean = super.shouldRunTest() && Language.findLanguageByID("SQL") != null

    private fun injectedAt(body: String, imports: String = "\"database/sql\""): List<String> {
        myFixture.configureByText("a.go", "package p\n\nimport (\n\t$imports\n)\n\n${body.trimIndent()}\n")
        val file = myFixture.file
        return if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file) && file.language.isKindOf(Language.findLanguageByID("SQL"))) listOf("SQL") else emptyList()
    }

    private val none = emptyList<String>()

    fun testDatabaseSqlMethods() {
        assertEquals(listOf("SQL"), injectedAt("func use(db *sql.DB) { db.Query(\"SELECT * FROM u<caret>sers WHERE id = ?\", 1) }"))
        assertEquals(listOf("SQL"), injectedAt("func use(tx *sql.Tx) { tx.ExecContext(nil, \"UPDATE users SET a<caret> = 1\") }"))
        assertEquals(listOf("SQL"), injectedAt("func use(c *sql.Conn) { c.QueryRowContext(nil, `SELECT 1 FROM d<caret>ual`) }"))
        assertEquals(listOf("SQL"), injectedAt("func use(db *sql.DB) { db.Prepare(\"DELETE FROM u<caret>sers\") }"))
    }

    fun testNamedConstant() {
        assertEquals(listOf("SQL"), injectedAt("const userQuery = `select * from u<caret>sers`"))
        assertEquals(listOf("SQL"), injectedAt("var insertSQL, other = `INSERT INTO t<caret> VALUES (1)`, 1"))
    }

    fun testNotInjected() {
        assertEquals(none, injectedAt("func use(db *sql.DB) { db.Query(\"SELECT 1\", \"SELECT <caret>2\") }"))
        // the first argument of the Context variant is the context, not the query
        assertEquals(none, injectedAt("func use(db *sql.DB) { db.QueryContext(\"SELECT <caret>1\", \"SELECT 2\") }"))
        assertEquals(none, injectedAt("func use() string { return fmt.Sprintf(\"SELECT * FROM %s<caret>\", \"t\") }", "\"fmt\""))
        assertEquals(none, injectedAt("func use(db *sql.DB) { db.Close(\"SELECT <caret>1\") }"))
        assertEquals(none, injectedAt("type Fake struct{}\nfunc (f *Fake) Query(q string) {}\nfunc use(f *Fake) { f.Query(\"SELECT <caret>1\") }"))
        assertEquals(none, injectedAt("const userQuery = \"select * from u<caret>sers\""))
        assertEquals(none, injectedAt("const userQuery = `just te<caret>xt`"))
        assertEquals(none, injectedAt("const userName = `select * from u<caret>sers`"))
    }

    fun testSqlx() {
        val tmp = FileUtil.createTempDirectory("gopsi-sqlx", null, true)
        File(tmp, "go.mod").writeText("module github.com/jmoiron/sqlx\n\ngo 1.22\n")
        File(tmp, "sqlx.go").writeText(
            "package sqlx\n\ntype DB struct{}\n\nfunc (db *DB) Select(dest any, query string, args ...any) error { return nil }\n" +
                "func (db *DB) Close(dest any, query string) {}\n",
        )
        File(tmp, "use.go").writeText("package sqlx\n\nfunc use(db *DB) {\n\tdb.Select(nil, \"SELECT * FROM users\")\n\tdb.Close(nil, \"SELECT * FROM users\")\n}\n")
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        PsiTestUtil.addContentRoot(myFixture.module, dir)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val file = dir.findChild("use.go")!!
            for ((needle, expected) in listOf("SELECT * FROM users\")\n\tdb.Close" to true, "SELECT * FROM users\")\n}" to false)) {
                myFixture.configureFromExistingVirtualFile(file)
                val offset = myFixture.file.text.indexOf(needle) + 3
                val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, offset)
                assertEquals(needle, expected, injected?.language?.isKindOf(Language.findLanguageByID("SQL")) == true)
            }
        } finally {
            PsiTestUtil.removeContentEntry(myFixture.module, dir)
        }
    }

    fun testPlaceholdersAreNotErrors() {
        for (q in listOf("SELECT * FROM users WHERE id = \$1 AND n = ?", "SELECT * FROM users WHERE id = :id AND n = @name")) {
            myFixture.configureByText("a.go", "package p\n\nimport \"database/sql\"\n\nfunc use(db *sql.DB) { db.Query(\"$q\") }\n")
            val errors = myFixture.doHighlighting().filter { it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.ERROR.myVal }
            assertEquals(q + " " + errors.map { it.description }, 0, errors.size)
        }
    }

    fun testDetectionIsPure() {
        assertTrue(GoSqlDetect.startsLikeSql("  \n select 1"))
        assertTrue(GoSqlDetect.startsLikeSql("With x as (select 1) select * from x"))
        assertFalse(GoSqlDetect.startsLikeSql("selection"))
        assertFalse(GoSqlDetect.startsLikeSql("hello"))
        assertTrue(GoSqlDetect.nameSaysQuery("userQuery") && GoSqlDetect.nameSaysQuery("loadSQL") && GoSqlDetect.nameSaysQuery("loadSql"))
        assertFalse(GoSqlDetect.nameSaysQuery("sqlText") || GoSqlDetect.nameSaysQuery("queryName"))
        assertEquals(2, GoSqlDetect.queryArgument("github.com/jmoiron/sqlx", "DB", "SelectContext"))
        assertEquals(1, GoSqlDetect.queryArgument("github.com/jackc/pgx/v5/pgxpool", "Pool", "Exec"))
        assertNull(GoSqlDetect.queryArgument("database/sql", "Rows", "Query"))
    }
}
