package io.github.golangsupport

import io.github.golangsupport.cli.GoEnvDiskCache
import io.github.golangsupport.cli.SingleFlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `go env` at project open: one process for every thread that asks (22-28 were seen), and the answer of the last session from disk. */
class GoEnvironmentCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun concurrentCallersShareOneComputation() {
        val flights = SingleFlight<String, String>()
        val runs = AtomicInteger()
        val release = CountDownLatch(1)
        val joined = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        val futures = (1..16).map {
            pool.submit<String> {
                flights.run("go", onDone = { joined.set(it) }) {
                    runs.incrementAndGet()
                    release.await(5, TimeUnit.SECONDS)
                    "answer"
                }
            }
        }
        // every caller is in before the one process answers
        val deadline = System.currentTimeMillis() + 5000
        while (runs.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(200)
        release.countDown()
        assertTrue(futures.all { it.get(5, TimeUnit.SECONDS) == "answer" })
        pool.shutdown()
        assertEquals("one process", 1, runs.get())
        assertEquals(16, joined.get())
    }

    @Test fun aFinishedFlightIsNotReusedAndAFailureReachesEveryCaller() {
        val flights = SingleFlight<String, Int>()
        assertEquals(1, flights.run("k") { 1 })
        assertEquals("the next call computes again", 2, flights.run("k") { 2 })
        val error = runCatching { flights.run("k") { throw IllegalStateException("no go") } }.exceptionOrNull()
        assertEquals("no go", error?.message)
        assertEquals(3, flights.run("k") { 3 })
    }

    @Test fun theKeyFollowsTheExecutableTheGoEnvFileAndTheEnvironment() {
        val go = temp.newFile("go.exe").apply { writeText("v1") }
        val envFile = temp.newFile("env").apply { writeText("GOPROXY=direct") }
        val environment = mapOf("GOFLAGS" to "-mod=mod", "PATH" to "x")
        val key = GoEnvDiskCache.key(go, envFile, environment)
        assertEquals("stable", key, GoEnvDiskCache.key(go, envFile, environment))
        assertEquals("PATH is not a part of it", key, GoEnvDiskCache.key(go, envFile, environment + ("PATH" to "y")))
        assertNotEquals(key, GoEnvDiskCache.key(go, envFile, environment + ("GOFLAGS" to "-tags=x")))
        assertNotEquals(key, GoEnvDiskCache.key(go, envFile, environment + ("GOTOOLCHAIN" to "local")))
        assertNotEquals("go env -w", key, GoEnvDiskCache.key(go, envFile.apply { setLastModified(lastModified() - 10_000) }, environment))
        val afterEnv = GoEnvDiskCache.key(go, envFile, environment)
        go.writeText("v2, a longer binary")
        assertNotEquals("go upgraded in place", afterEnv, GoEnvDiskCache.key(go, envFile, environment))
        assertNotEquals(GoEnvDiskCache.key(go, envFile, environment), GoEnvDiskCache.key(File(temp.root, "other-go.exe"), envFile, environment))
    }

    @Test fun storedAnswerRoundTripsAndIsValidOnlyUnderItsKey() {
        val stored = GoEnvDiskCache.Stored("k1", mapOf("GOROOT" to "C:\\Go", "GOVERSION" to "go1.24.2"), 1234L)
        val decoded = GoEnvDiskCache.decode(GoEnvDiskCache.encode(stored))
        assertNotNull(decoded)
        assertEquals(stored.values, decoded!!.values)
        assertEquals(1234L, decoded.savedAt)
        assertNotNull(GoEnvDiskCache.valid(decoded, "k1"))
        assertNull("stale", GoEnvDiskCache.valid(decoded, "k2"))
        assertNull("garbage", GoEnvDiskCache.decode("{not json"))
        assertNull("an empty answer is not kept", GoEnvDiskCache.decode(GoEnvDiskCache.encode(GoEnvDiskCache.Stored("k", emptyMap(), 1))))
    }

    @Test fun theGoEnvFileIsWhereGoKeepsIt() {
        assertEquals(File(File("C:\\Users\\u\\AppData\\Roaming", "go"), "env"), GoEnvDiskCache.goEnvFile(mapOf("APPDATA" to "C:\\Users\\u\\AppData\\Roaming"), null, windows = true, mac = false, home = "C:\\Users\\u"))
        assertEquals(File("/home/u/.config/go/env"), GoEnvDiskCache.goEnvFile(emptyMap(), null, windows = false, mac = false, home = "/home/u"))
        assertEquals(File("/x/go/env"), GoEnvDiskCache.goEnvFile(mapOf("XDG_CONFIG_HOME" to "/x"), null, windows = false, mac = false, home = "/home/u"))
        assertEquals(File("/Users/u/Library/Application Support/go/env"), GoEnvDiskCache.goEnvFile(emptyMap(), null, windows = false, mac = true, home = "/Users/u"))
        assertEquals("what the last answer said", File("/custom/env"), GoEnvDiskCache.goEnvFile(emptyMap(), mapOf("GOENV" to "/custom/env"), windows = false, mac = false, home = "/home/u"))
        assertEquals("the environment wins", File("/mine"), GoEnvDiskCache.goEnvFile(mapOf("GOENV" to "/mine"), mapOf("GOENV" to "/custom/env"), windows = false, mac = false, home = "/home/u"))
        assertNull(GoEnvDiskCache.goEnvFile(mapOf("GOENV" to "off"), null, windows = false, mac = false, home = "/home/u"))
    }
}
