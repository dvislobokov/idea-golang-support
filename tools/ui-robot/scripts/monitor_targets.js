// The processes the Go Monitor knows, and the notifications shown so far.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.notification.NotificationsManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const targets = project.getService(cls("io.github.golangsupport.monitor.RunningGoProcesses")).targets()
let out = "monitor targets: " + targets.size() + "\n"
for (let i = 0; i < targets.size(); i++) out += "  " + targets.get(i).getTitle() + " withChildren=" + targets.get(i).getWithChildren() + "\n"
const notifications = NotificationsManager.getNotificationsManager().getNotificationsOfType(java.lang.Class.forName("com.intellij.notification.Notification"), project)
out += "notifications: " + notifications.length + "\n"
for (let i = 0; i < notifications.length; i++) out += "  [" + notifications[i].getType() + "] " + notifications[i].getTitle() + " :: " + String(notifications[i].getContent()).substring(0, 200).split("\n").join(" ") + "\n"
out
