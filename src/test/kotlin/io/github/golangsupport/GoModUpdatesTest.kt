package io.github.golangsupport

import io.github.golangsupport.mod.GoModFile
import io.github.golangsupport.mod.GoModIssues
import io.github.golangsupport.mod.GoModuleList
import io.github.golangsupport.mod.GoModUpdates
import org.junit.Assert.assertEquals
import org.junit.Test

class GoModUpdatesTest {
    private val requires = GoModFile.parse("module m\n\ngo 1.24\n\nrequire (\n\tgithub.com/google/uuid v1.6.0\n\tgolang.org/x/text v0.20.0 // indirect\n)\n").requires

    @Test
    fun aRequireWithANewerVersionIsOutdated() {
        val outdated = GoModUpdates.outdated(requires, mapOf("github.com/google/uuid" to "v1.7.0", "golang.org/x/sys" to "v0.30.0"))
        assertEquals(listOf("github.com/google/uuid" to "v1.7.0"), outdated.map { it.first.path to it.second })
        assertEquals(5, outdated.single().first.line)
    }

    @Test
    fun anAlreadyUpgradedRequireIsNotReported() {
        // the cache answers until the next check: after `go get` the file has the version the proxy named
        assertEquals(emptyList<Any>(), GoModUpdates.outdated(requires, mapOf("github.com/google/uuid" to "v1.6.0")))
    }

    @Test
    fun latestIsNeverADowngrade() {
        assertEquals(true, GoModUpdates.newer("v1.7.0", "v1.6.0"))
        assertEquals(true, GoModUpdates.newer("v1.10.0", "v1.9.3"))
        assertEquals(false, GoModUpdates.newer("v1.6.0", "v1.7.0-rc.1"))
        assertEquals(true, GoModUpdates.newer("v0.1.0", "v0.0.0-20230101000000-abcdef123456"))
        assertEquals(false, GoModUpdates.newer("v1.6.0", "v1.6.0"))
        assertEquals(true, GoModUpdates.newer("v2.0.0+incompatible", "v1.9.0"))
        assertEquals(false, GoModUpdates.newer("latest", "v1.0.0"))
    }

    @Test
    fun deprecatedModulesAndRetractedVersionsFromGoList() {
        // `go list -m -e -u -json m@latest` and `go list -m -e -retracted -json m@version`: one JSON object after another
        val latest = """
            {"Path": "github.com/google/uuid", "Version": "v1.7.0", "Deprecated": "use github.com/gofrs/uuid instead"}
            {"Path": "golang.org/x/text", "Version": "v0.21.0"}
            {"Path": "example.com/gone", "Error": {"Err": "not found"}}
        """.trimIndent()
        val exact = """
            {"Path": "github.com/google/uuid", "Version": "v1.6.0"}
            {"Path": "golang.org/x/text", "Version": "v0.20.0", "Retracted": ["broken build", "data race"]}
        """.trimIndent()
        val issues = GoModIssues(GoModIssues.deprecated(GoModuleList.parse(latest)), GoModIssues.retracted(GoModuleList.parse(exact)))
        assertEquals(mapOf("github.com/google/uuid" to "use github.com/gofrs/uuid instead"), issues.deprecated)
        assertEquals(mapOf("golang.org/x/text@v0.20.0" to listOf("broken build", "data race")), issues.retracted)
        assertEquals(
            listOf(
                Triple(5, true, "Module 'github.com/google/uuid' is deprecated: use github.com/gofrs/uuid instead"),
                Triple(6, false, "Version v0.20.0 of 'golang.org/x/text' is retracted: broken build; data race"),
            ),
            GoModIssues.problems(requires, issues).map { Triple(it.first.line, it.second, it.third) },
        )
    }

    @Test
    fun anotherRequiredVersionIsNotRetracted() {
        val issues = GoModIssues(emptyMap(), mapOf("golang.org/x/text@v0.19.0" to listOf("broken")))
        assertEquals(emptyList<Any>(), GoModIssues.problems(requires, issues))
    }

    @Test
    fun updateLinesTargetAllOrDirectRequires() {
        assertEquals(listOf("github.com/google/uuid@latest", "golang.org/x/text@latest"), io.github.golangsupport.mod.GoModCodeVision.targets(requires, direct = false))
        assertEquals(listOf("github.com/google/uuid@latest"), io.github.golangsupport.mod.GoModCodeVision.targets(requires, direct = true))
        assertEquals(4, io.github.golangsupport.mod.GoModCodeVision.anchorLine("module m\n\ngo 1.24\n\nrequire (\n\tx v1\n)\n"))
        assertEquals(2, io.github.golangsupport.mod.GoModCodeVision.anchorLine("module m\n// requirements\nrequire x v1\n"))
    }
}
