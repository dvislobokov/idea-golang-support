// Restarts the highlighting of every open project (what the settings pages do after a switch changed).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.codeInsight.daemon.DaemonCodeAnalyzer)
var ps = ProjectManager.getInstance().getOpenProjects()
for (var i = 0; i < ps.length; i++) DaemonCodeAnalyzer.getInstance(ps[i]).restart()
"restarted " + ps.length
