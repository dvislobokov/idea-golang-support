// Switches the auto-test of the Go Tests window (rerun the tests of a package on save) to __VALUE__.
importClass(com.intellij.openapi.project.ProjectManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var autoTest = kotlinObject("io.github.golangsupport.testing.GoAutoTest")
autoTest.setEnabled(project, __VALUE__)
"autotest enabled = " + autoTest.isEnabled(project)
