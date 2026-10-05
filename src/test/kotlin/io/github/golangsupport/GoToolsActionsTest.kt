package io.github.golangsupport

import io.github.golangsupport.build.GoVetFileAction
import io.github.golangsupport.format.GoImportsFileAction
import io.github.golangsupport.mod.GoModuleRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tools | Go Tools and the module roots of the project view: the command lines and which directories qualify. */
class GoToolsActionsTest {
    @Test
    fun vetFileVetsThePackageOfTheFile() {
        assertEquals(listOf("vet", "."), GoVetFileAction.vetFileArguments(emptyList()))
        assertEquals(listOf("vet", "-tags=integration", "."), GoVetFileAction.vetFileArguments(listOf("-tags=integration")))
    }

    @Test
    fun goimportsRewritesTheFile() {
        assertEquals(listOf("-w", "./main.go"), GoImportsFileAction.arguments("main.go"))
        assertEquals(listOf("-w", "./-x.go"), GoImportsFileAction.arguments("-x.go"))  // never a flag
    }

    @Test
    fun onlyAModuleOutsideTheProjectIsAttached() {
        val roots = listOf("C:/work/app")
        assertNull(GoModuleRoots.attachProblem("C:/work/lib", hasGoMod = true, contentRoots = roots))
        assertNotNull("GOPATH directories are not supported", GoModuleRoots.attachProblem("C:/work/lib", hasGoMod = false, contentRoots = roots))
        assertNotNull("already in the project", GoModuleRoots.attachProblem("C:/work/app/internal/x", hasGoMod = true, contentRoots = roots))
        assertNotNull(GoModuleRoots.attachProblem("C:/work/app", hasGoMod = true, contentRoots = roots))
    }

    @Test
    fun onlyAnAttachedRootIsDetached() {
        assertTrue(GoModuleRoots.isDetachable("C:/work/lib", isContentRoot = true, projectDirectory = "C:/work/app"))
        assertFalse("the project directory itself", GoModuleRoots.isDetachable("C:/work/app", isContentRoot = true, projectDirectory = "C:/work/app"))
        assertFalse("a root inside the project", GoModuleRoots.isDetachable("C:/work/app/tools", isContentRoot = true, projectDirectory = "C:/work/app"))
        assertFalse("not a root", GoModuleRoots.isDetachable("C:/work/lib/x", isContentRoot = false, projectDirectory = "C:/work/app"))
    }
}
