// What the New Project dialogs would list of the plugin: the generator wizards that are enabled (the dialog of IntelliJ IDEA) and the
// directory generators (the dialog of the other IDEs). With __OPEN__ = true the dialog of this IDE is opened as well, for a picture.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ActionPlaces)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.openapi.wm.WindowManager)
importClass(com.intellij.ide.DataManager)
var lines = []
var wizards = com.intellij.ide.wizard.GeneratorNewProjectWizard.EP_NAME.getExtensionList()
for (var i = 0; i < wizards.size(); i++) {
    var wizard = wizards.get(i)
    lines.push("wizard: " + wizard.getName() + " enabled=" + wizard.isEnabled() + " " + wizard.getClass().getName())
}
var generators = com.intellij.platform.DirectoryProjectGenerator.EP_NAME.getExtensionList()
for (var j = 0; j < generators.size(); j++) {
    var generator = generators.get(j)
    lines.push("directory generator: " + generator.getName() + " " + generator.getClass().getName())
}
lines.push("platform prefix: " + com.intellij.util.PlatformUtils.getPlatformPrefix())
if (__OPEN__) {
    var projects = ProjectManager.getInstance().getOpenProjects()
    var project = projects[projects.length - 1]
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        var action = ActionManager.getInstance().getAction("NewProject")
        var component = WindowManager.getInstance().getFrame(project).getComponent()
        ActionUtil.invokeAction(action, DataManager.getInstance().getDataContext(component), ActionPlaces.MAIN_MENU, null, null)
    } }))
}
lines.join("\n")
