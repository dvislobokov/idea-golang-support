// The notifications of the project (balloons and the Notifications window): title, content and the titles of their actions.
importClass(com.intellij.notification.Notification)
importClass(com.intellij.notification.NotificationsManager)
importClass(com.intellij.openapi.project.ProjectManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var all = NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification, project)
var out = []
for (var i = 0; i < all.length; i++) {
    var actions = all[i].getActions()
    var titles = []
    for (var a = 0; a < actions.size(); a++) titles.push(actions.get(a).getTemplatePresentation().getText())
    out.push(all[i].getType() + " '" + all[i].getTitle() + "': " + all[i].getContent() + (titles.length > 0 ? "  [" + titles.join(", ") + "]" : ""))
}
out.length == 0 ? "no notifications" : out.join("\n")
