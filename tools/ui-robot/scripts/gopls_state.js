// The state of every gopls client of the last open project.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.platform.lsp.api.LspClientManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var clients = LspClientManager.getInstance(project).getClients(cls("io.github.golangsupport.lsp.GoplsIntegrationProvider")).toArray()
var out = "gopls clients: " + clients.length
for (var i = 0; i < clients.length; i++) out += " [" + clients[i].getState() + "]"
out
