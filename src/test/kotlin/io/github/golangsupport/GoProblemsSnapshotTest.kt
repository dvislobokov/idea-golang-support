package io.github.golangsupport

import io.github.golangsupport.ci.GoSarifLevel
import io.github.golangsupport.problems.GoFinding
import io.github.golangsupport.problems.GoProblemsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class GoProblemsSnapshotTest {
    @Test fun theFingerprintIgnoresOrderAndSeesEveryChange() {
        val a = GoProblemsSnapshot.fileLine("/p/a.go", 10, 100)
        val b = GoProblemsSnapshot.fileLine("/p/b.go", 20, 200)
        assertEquals(GoProblemsSnapshot.fingerprint(listOf(a, b)), GoProblemsSnapshot.fingerprint(listOf(b, a)))
        assertNotEquals(GoProblemsSnapshot.fingerprint(listOf(a, b)), GoProblemsSnapshot.fingerprint(listOf(a, GoProblemsSnapshot.fileLine("/p/b.go", 20, 201))))
        assertNotEquals(GoProblemsSnapshot.fingerprint(listOf(a, b)), GoProblemsSnapshot.fingerprint(listOf(a)))
    }

    @Test fun onlyFileChangesAreTakenOneByOne() {
        val a = GoProblemsSnapshot.fileLine("/p/a b.go", 10, 100)
        val b = GoProblemsSnapshot.fileLine("/p/b.go", 20, 200)
        val then = listOf(a, b, "plugin\t1.0\t1")
        val changes = GoProblemsSnapshot.fileChanges(then, listOf(GoProblemsSnapshot.fileLine("/p/a b.go", 11, 101), GoProblemsSnapshot.fileLine("/p/c.go", 1, 1), "plugin\t1.0\t1"))!!
        assertEquals(listOf("/p/a b.go", "/p/c.go"), changes.changed)
        assertEquals(listOf("/p/b.go"), changes.removed)
        assertNull("the plugin changed", GoProblemsSnapshot.fileChanges(then, listOf(a, b, "plugin\t1.1\t1")))
        assertNull("an old snapshot without inputs", GoProblemsSnapshot.fileChanges(emptyList(), listOf(a)))
        assertEquals(0, GoProblemsSnapshot.fileChanges(then, then.reversed())!!.changed.size)
    }

    @Test fun theSnapshotGoesThroughTheDisk() {
        val file = Files.createTempDirectory("go-problems").resolve("p.json")
        val finding = GoFinding(3, 1, GoSarifLevel.WARNING, "GoUnusedVariable", "Unused variable", "unused variable 'x' <\"quoted\">")
        GoProblemsSnapshot.write(file, GoProblemsSnapshot.Snapshot(GoProblemsSnapshot.FORMAT, "abc", mapOf("/p/a.go" to listOf(finding)), listOf("go 1.27")))
        val read = GoProblemsSnapshot.read(file)!!
        assertEquals("abc", read.fingerprint)
        assertEquals(listOf(finding), read.files["/p/a.go"])
        assertEquals(listOf("go 1.27"), read.inputs)
        assertEquals(listOf("-go 1.27", "+go 1.28"), GoProblemsSnapshot.difference(read.inputs, listOf("go 1.28")))
    }

    @Test fun anotherFormatOrGarbageIsNoSnapshot() {
        val dir = Files.createTempDirectory("go-problems")
        assertNull(GoProblemsSnapshot.read(dir.resolve("missing.json")))
        Files.writeString(dir.resolve("old.json"), "{\"format\":0,\"fingerprint\":\"x\",\"files\":{}}")
        assertNull(GoProblemsSnapshot.read(dir.resolve("old.json")))
        Files.writeString(dir.resolve("bad.json"), "not json")
        assertNull(GoProblemsSnapshot.read(dir.resolve("bad.json")))
    }
}
