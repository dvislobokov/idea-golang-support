// Closes the open project named __NAME__ (its frame goes away; the other projects stay), prints the names of the projects left open.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
var name = "__NAME__"   // once: the robot's substitution replaces the first occurrence of a line only
var manager = ProjectManager.getInstance()
var projects = manager.getOpenProjects()
var closed = false
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    for (var i = 0; i < projects.length; i++) if (projects[i].getName() == name) { manager.closeAndDispose(projects[i]); closed = true }
}}), ModalityState.any())
var left = manager.getOpenProjects(), names = []
for (var j = 0; j < left.length; j++) names.push(left[j].getName());   // the `;` keeps Rhino from calling the push result with the next line
var result = (closed ? "closed " : "no project ") + name + "; open: " + names.join(", ")
result
