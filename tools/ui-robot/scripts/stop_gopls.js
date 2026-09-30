// Stops the gopls clients of the project through the LSP client manager of the platform (the way "no server yet" looks), and prints how many remain.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.platform.lsp.api.LspClientManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var provider = cls("io.github.golangsupport.lsp.GoplsIntegrationProvider")
var manager = LspClientManager.getInstance(project)
manager.stopClients(provider)
java.lang.Thread.sleep(2000)
"clients after stop: " + manager.getClients(provider).size()
