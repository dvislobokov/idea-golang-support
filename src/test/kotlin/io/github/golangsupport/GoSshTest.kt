package io.github.golangsupport

import io.github.golangsupport.run.GoLaunchArguments
import io.github.golangsupport.run.GoSsh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoSshTest {
    @Test
    fun platforms() {
        assertEquals("linux" to "amd64", GoSsh.platform("Linux x86_64"))
        assertEquals("linux" to "arm64", GoSsh.platform("Linux aarch64"))
        assertEquals("darwin" to "arm64", GoSsh.platform("Darwin arm64"))
        assertNull(GoSsh.platform("MINGW64_NT-10.0 x86_64"))
        assertNull(GoSsh.platform("Linux mips"))
        assertNull(GoSsh.platform(""))
    }

    @Test
    fun probeSkipsTheBannerBeforeTheMark() {
        val host = GoSsh.probe("Welcome to Ubuntu 24.04\n * Documentation: https://help.ubuntu.com\ngo-project-support-probe\nLinux x86_64\n/home/me\n")!!
        assertEquals("linux", host.goos)
        assertEquals("amd64", host.goarch)
        assertEquals("/home/me", host.home)
        assertNull(GoSsh.probe("Permission denied (publickey).\n"))
        assertNull(GoSsh.probe("go-project-support-probe\nSunOS i86pc\n/export/home/me\n"))
    }

    @Test
    fun directoryIsUnderHomeUnlessAbsolute() {
        assertEquals("/home/me/.cache/go-project-support", GoSsh.directory(null, "/home/me"))
        assertEquals("/home/me/debug", GoSsh.directory("~/debug/", "/home/me/"))
        assertEquals("/home/me/debug", GoSsh.directory("debug", "/home/me"))
        assertEquals("/opt/app", GoSsh.directory("/opt/app/", "/home/me"))
        assertEquals("/home/me", GoSsh.directory("~", "/home/me"))
    }

    @Test
    fun quotingSurvivesQuotes() {
        assertEquals("'a b'", GoSsh.quote("a b"))
        assertEquals("'it'\\''s'", GoSsh.quote("it's"))
    }

    @Test
    fun uploadGoesThroughATemporaryName() {
        val script = GoSsh.uploadScript("/home/me/.cache/x/dlv")
        assertTrue(script, script.startsWith("mkdir -p '/home/me/.cache/x' && cat > '/home/me/.cache/x/dlv.part'"))
        assertTrue(script, script.endsWith("mv -f '/home/me/.cache/x/dlv.part' '/home/me/.cache/x/dlv'"))
    }

    @Test
    fun serverListensOnLoopbackAndDiesWithTheSession() {
        val script = GoSsh.serverScript("/home/me/d/dlv", "/home/me/d", 0, anyGoVersion = true, log = false)
        assertTrue(script, "--listen=127.0.0.1:0" in script)
        assertTrue(script, "--check-go-version=false" in script)
        assertFalse(script, "--log" in script)
        // the watcher reads the saved stdin, not the /dev/null a background command gets
        assertTrue(script, "exec 3<&0" in script && "cat <&3" in script && "kill \$p" in script)
        assertTrue(GoSsh.serverScript("dlv", "/d", 4000, anyGoVersion = false, log = true).let { "127.0.0.1:4000" in it && "--log-output=dap,debugger" in it })
    }

    @Test
    fun takenPortAndFailures() {
        assertTrue(GoSsh.portTaken("Error: listen tcp 127.0.0.1:2345: bind: address already in use"))
        assertFalse(GoSsh.portTaken("DAP server listening at: 127.0.0.1:2345"))
        assertEquals("ssh: connect to host h port 22: Connection refused", GoSsh.failure("\nssh: connect to host h port 22: Connection refused\n"))
        assertEquals("no output", GoSsh.failure(""))
    }

    @Test
    fun programNamesArePerPackage() {
        val run = GoSsh.programName("C:\\work\\shop\\cmd\\api", test = false)
        assertEquals(run, GoSsh.programName("c:/work/shop/cmd/api", test = false))
        assertNotEquals(run, GoSsh.programName("C:\\work\\shop\\cmd\\worker", test = false))
        assertTrue(GoSsh.programName("C:\\work\\shop\\store", test = true).let { it.startsWith("test-") && it.endsWith(".test") })
    }

    @Test
    fun testSelectionOfABinary() {
        assertEquals(listOf("-test.v"), GoLaunchArguments.testArguments(false, null))
        assertEquals(listOf("-test.v", "-test.run", "^TestOrder$"), GoLaunchArguments.testArguments(false, "^TestOrder$"))
        assertEquals(listOf("-test.run", "^$", "-test.bench", "."), GoLaunchArguments.testArguments(true, " "))
    }
}
