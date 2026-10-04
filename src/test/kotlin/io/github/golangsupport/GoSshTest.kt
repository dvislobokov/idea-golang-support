package io.github.golangsupport

import io.github.golangsupport.run.GoLaunchArguments
import io.github.golangsupport.run.GoSsh
import io.github.golangsupport.run.GoTarWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun hostsThatLookLikeOptionsAreRefused() {
        assertNull(GoSsh.invalidHost("me@build-01"))
        assertNull(GoSsh.invalidHost("ssh://me@host:2222"))
        assertNotNull(GoSsh.invalidHost("-oProxyCommand=calc.exe"))
        assertNotNull(GoSsh.invalidHost("host -oProxyCommand=x"))
        assertNotNull(GoSsh.invalidHost(" "))
        assertThrows(IllegalArgumentException::class.java) { GoSsh.commandLine("ssh", "-oProxyCommand=x", "true") }
        val command = GoSsh.commandLine("ssh", "me@host", "true").parametersList.list
        assertEquals("--", command[command.indexOf("me@host") - 1])
        val tunnel = GoSsh.tunnelCommandLine("ssh", "me@host", "/tmp/tmp.x/dlv.sock").parametersList.list
        assertEquals(listOf("-W", "/tmp/tmp.x/dlv.sock", "--", "me@host"), tunnel.takeLast(4))
    }

    @Test
    fun uploadGoesThroughATemporaryName() {
        val script = GoSsh.uploadScript("/home/me/.cache/x/dlv")
        assertTrue(script, script.startsWith("umask 077; cat > '/home/me/.cache/x/dlv.part' && chmod 700"))
        assertTrue(script, script.endsWith("mv -f '/home/me/.cache/x/dlv.part' '/home/me/.cache/x/dlv'"))
    }

    @Test
    fun setupMakesThePluginDirectoryPrivateAndRefusesOthersWritable() {
        val own = GoSsh.setupScript("/home/me/.cache/g", "/home/me/.cache/g/runs/run-1", own = true)
        assertTrue(own, own.startsWith("umask 077; mkdir -p '/home/me/.cache/g' || exit 1; chmod 700 '/home/me/.cache/g'; "))
        assertTrue(own, "?????w*|????????w*) echo ${GoSsh.UNSAFE_MARK}; exit 3" in own)
        assertTrue(own, own.endsWith("chmod 700 '/home/me/.cache/g/bin' '/home/me/.cache/g/runs' '/home/me/.cache/g/runs/run-1'"))
        assertFalse(GoSsh.setupScript("/srv/debug", "/srv/debug/runs/run-1", own = false).contains("chmod 700 '/srv/debug';"))
        assertEquals("/d/bin/dlv-linux-amd64-0123456789ab", GoSsh.delvePath("/d", "dlv-linux-amd64", "0123456789abcdef"))
    }

    @Test
    fun serverListensOnAPrivateSocketAndDiesWithTheSession() {
        val script = GoSsh.serverScript("/home/me/d/bin/dlv", "/home/me/d", anyGoVersion = true, log = false)
        assertTrue(script, script.startsWith("umask 077; d=\$(mktemp -d) || exit 1; "))
        assertTrue(script, "dap --listen=unix:\$d/dlv.sock --check-go-version=false" in script)
        assertFalse(script, "127.0.0.1" in script)
        assertFalse(script, "--log" in script)
        // the watcher reads the saved stdin, not the /dev/null a background command gets
        assertTrue(script, "exec 3<&0" in script && "cat <&3" in script && "kill \$p" in script)
        assertTrue(script, script.endsWith("wait \$p; rm -rf \"\$d\""))
        assertTrue(GoSsh.serverScript("dlv", "/d", anyGoVersion = false, log = true).let { "--log-output=dap,debugger" in it && "--check-go-version" !in it })
    }

    @Test
    fun socketLineAndFailures() {
        assertEquals("/tmp/tmp.AbC123/dlv.sock", GoSsh.socketPath("DAP server listening at: /tmp/tmp.AbC123/dlv.sock"))
        assertNull(GoSsh.socketPath("DAP server listening at: 127.0.0.1:2345"))
        assertEquals("ssh: connect to host h port 22: Connection refused", GoSsh.failure("\nssh: connect to host h port 22: Connection refused\n"))
        assertTrue(GoSsh.failure("channel 0: open failed: administratively prohibited: open failed").contains("AllowStreamLocalForwarding"))
        assertEquals("no output", GoSsh.failure(""))
    }

    @Test
    fun runDirectoriesArePerPackageAndProgramsNamedAfterIt() {
        val run = GoSsh.runDirectory("/d", "C:\\work\\shop\\cmd\\api", test = false)
        assertTrue(run, run.startsWith("/d/runs/run-"))
        assertEquals(run, GoSsh.runDirectory("/d", "c:/work/shop/cmd/api", test = false))
        assertNotEquals(run, GoSsh.runDirectory("/d", "C:\\work\\shop\\cmd\\worker", test = false))
        assertTrue(GoSsh.runDirectory("/d", "C:\\work\\shop\\store", test = true).startsWith("/d/runs/test-"))
        assertEquals("api", GoSsh.programName("C:\\work\\shop\\cmd\\api\\", test = false))
        assertEquals("store.test", GoSsh.programName("C:/work/shop/store", test = true))
    }

    @Test
    fun filesToCopyLines() {
        val entries = GoSsh.fileEntries("# keys\nconfig.yaml\n\n certs/ca.pem = ~/.config/app/ca.pem \nseed.sql=\n")
        assertEquals(listOf("config.yaml", "certs/ca.pem", "seed.sql"), entries.map { it.local })
        assertEquals(listOf(null, "~/.config/app/ca.pem", null), entries.map { it.remote })
        assertEquals("config.yaml", GoSsh.remotePath(null, "config.yaml"))
        assertEquals("conf/app.yaml", GoSsh.remotePath("./conf//app.yaml/", "x.yaml"))
        assertEquals("shared.yaml", GoSsh.remotePath("conf/../shared.yaml", "x"))
        // a configuration shared with a project writes into the run directory only
        assertNull(GoSsh.remotePath("~/.ssh/authorized_keys", "x"))
        assertNull(GoSsh.remotePath("/etc/app", "x"))
        assertNull(GoSsh.remotePath("../shared.yaml", "x"))
        assertNull(GoSsh.remotePath("a/../..", "x"))
        assertNull(GoSsh.remotePath(".", "x"))
        assertEquals("certs/ca.pem", GoSsh.defaultRemote("certs\\ca.pem", "ca.pem"))
        assertEquals("ca.pem", GoSsh.defaultRemote("../shared/ca.pem", "ca.pem"))
        assertEquals("ca.pem", GoSsh.defaultRemote("C:\\work\\shop\\certs\\ca.pem", "ca.pem"))
        assertEquals("ca.pem", GoSsh.defaultRemote("/work/shop/certs/ca.pem", "ca.pem"))
    }

    @Test
    fun insideFollowsLinks() {
        val root = java.nio.file.Files.createTempDirectory("ssh-root").toRealPath()
        val outside = java.nio.file.Files.createTempDirectory("ssh-outside").toRealPath()
        try {
            val file = java.nio.file.Files.writeString(root.resolve("a.yaml"), "x")
            assertTrue(GoSsh.isInside(file, root))
            assertFalse(GoSsh.isInside(root.resolve("../" + outside.fileName), root))
            assertFalse(GoSsh.isInside(root.resolve("missing"), root))
            val secret = java.nio.file.Files.writeString(outside.resolve("id_rsa"), "x")
            val link = root.resolve("link")
            if (runCatching { java.nio.file.Files.createSymbolicLink(link, secret) }.isSuccess) assertFalse(GoSsh.isInside(link, root))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun fingerprintIsTheSetNotItsOrder() {
        val a = GoSsh.Copied("/r/a", 10, 1000)
        val b = GoSsh.Copied("/r/b", 20, 2000)
        assertEquals(GoSsh.fingerprint(listOf(a, b)), GoSsh.fingerprint(listOf(b, a)))
        assertNotEquals(GoSsh.fingerprint(listOf(a, b)), GoSsh.fingerprint(listOf(a, GoSsh.Copied("/r/b", 20, 2001))))
        assertNotEquals(GoSsh.fingerprint(listOf(a)), GoSsh.fingerprint(listOf(a, b)))
        assertTrue(GoSsh.unpackScript("/r", "abc").let { it.startsWith("umask 077; tar -x -f - -C '/r' && ") && it.endsWith("> '/r/.files'") })
    }

    @Test
    fun tarHeadersAreUstarWithPrivateModes() {
        val bytes = java.io.ByteArrayOutputStream()
        val tar = GoTarWriter(bytes)
        tar.file("home/me/run/config.yaml", 3, 1_700_000_000_000, "a: 1".byteInputStream().also { it.skip(1) })
        tar.finish()
        val out = bytes.toByteArray()
        assertEquals(512 * 4, out.size)
        assertEquals("home/me/run/config.yaml", String(out, 0, 23))
        assertEquals("0000600", String(out, 100, 7))
        assertEquals("00000000003", String(out, 124, 11))
        assertEquals('0'.code.toByte(), out[156])
        assertEquals("ustar", String(out, 257, 5))
        val stored = String(out, 148, 6).toInt(8)
        val computed = out.copyOfRange(0, 512).also { it.fill(' '.code.toByte(), 148, 156) }.sumOf { it.toInt() and 0xff }
        assertEquals(computed, stored)
        assertEquals(": 1", String(out, 512, 3))

        val long = "home/me/" + "d".repeat(90) + "/" + "f".repeat(60)
        assertEquals("home/me/" + "d".repeat(90) to "f".repeat(60), GoTarWriter.split(long))
        assertThrows(IllegalArgumentException::class.java) { GoTarWriter.split("x/" + "f".repeat(120)) }
        assertThrows(IllegalStateException::class.java) { GoTarWriter(java.io.ByteArrayOutputStream()).file("a", 5, 0, "abc".byteInputStream()) }
    }

    @Test
    fun testSelectionOfABinary() {
        assertEquals(listOf("-test.v"), GoLaunchArguments.testArguments(false, null))
        assertEquals(listOf("-test.v", "-test.run", "^TestOrder$"), GoLaunchArguments.testArguments(false, "^TestOrder$"))
        assertEquals(listOf("-test.run", "^$", "-test.bench", "."), GoLaunchArguments.testArguments(true, " "))
    }
}
