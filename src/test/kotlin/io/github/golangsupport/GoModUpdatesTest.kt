package io.github.golangsupport

import io.github.golangsupport.mod.GoModFile
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
}
