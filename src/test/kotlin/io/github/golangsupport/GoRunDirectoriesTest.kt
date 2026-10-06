package io.github.golangsupport

import io.github.golangsupport.run.GoRunDirectories
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The default working directory of `go run` / Debug: the module root, not the package directory (a program reads ./config.yaml there). */
class GoRunDirectoriesTest {
    private val root = Files.createTempDirectory("go-run-dirs").toFile().absoluteFile

    private fun dir(path: String): File = File(root, path).also { it.mkdirs() }

    @Test fun theNearestGoModUpwardsWins() {
        Files.writeString(dir("module").resolve("go.mod").toPath(), "module m\n")
        val pkg = dir("module/cmd/backendapi")
        assertEquals(File(root, "module").path, GoRunDirectories.moduleRoot(pkg.path, root.path))
    }

    @Test fun thePackageDirectoryItselfMayBeTheModule() {
        val pkg = dir("single")
        Files.writeString(pkg.resolve("go.mod").toPath(), "module s\n")
        assertEquals(pkg.path, GoRunDirectories.moduleRoot(pkg.path, root.path))
    }

    @Test fun withoutGoModTheProjectDirectoryIsUsed() {
        val pkg = dir("plain/cmd/x")
        assertEquals(File(root, "plain").path, GoRunDirectories.moduleRoot(pkg.path, File(root, "plain").path))
    }

    @Test fun aPackageOutsideTheProjectStaysWhereItIs() {
        val pkg = dir("elsewhere/cmd/x")
        assertEquals(pkg.path, GoRunDirectories.moduleRoot(pkg.path, File(root, "plain").path))
        assertEquals(pkg.path, GoRunDirectories.moduleRoot(pkg.path, null))
    }
}

/** The program scan of a project reads the text alone: no PSI, no stub building under a read action (a frozen IDE, seen live). */
class GoProgramTextTest {
    @Test fun aMainPackageWithAMainIsAProgram() = assertEquals(true, io.github.golangsupport.run.GoRunConfigurationGenerator.isProgramText("package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println()\n}\n"))

    @Test fun aCommentAfterThePackageClauseIsFine() = assertEquals(true, io.github.golangsupport.run.GoRunConfigurationGenerator.isProgramText("// Command x.\npackage main // the program\n\nfunc main() {}\n"))

    @Test fun anotherPackageIsNot() = assertEquals(false, io.github.golangsupport.run.GoRunConfigurationGenerator.isProgramText("package store\n\nfunc main() {}\n"))

    @Test fun aMainPackageWithoutAMainIsNot() = assertEquals(false, io.github.golangsupport.run.GoRunConfigurationGenerator.isProgramText("package main\n\nfunc helper() {}\n\nvar mainCount int\n"))

    @Test fun aMethodNamedMainIsNot() = assertEquals(false, io.github.golangsupport.run.GoRunConfigurationGenerator.isProgramText("package main\n\ntype t int\n\nfunc (t) main() {}\n"))
}
