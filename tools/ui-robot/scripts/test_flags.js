// A temporary Go test configuration of __DIR__ with -short, -failfast and -timeout __TIMEOUT__: prints the go command it would run and
// the arguments of the launch request delve would get (the flags of the test binary).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.execution.RunManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var type = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(cls("io.github.golangsupport.run.GoConfigurationType"))
var factory = type.getConfigurationFactories()[0]
var settings = RunManager.getInstance(project).createConfiguration("flags probe", factory)
var configuration = settings.getConfiguration()
var options = configuration.getOptions()
var command = cls("io.github.golangsupport.run.GoCommand")
options.setCommand(java.lang.Enum.valueOf(command, "TEST"))
options.setTarget("__DIR__")
options.setShort(true)
options.setFailFast(true)
options.setTimeout("__TIMEOUT__")
var line = configuration.buildCommandLine(null, null).getCommandLineString()
var launch = configuration.debugLaunchArguments()
"go: " + line + "\nlaunch args: " + launch.get("args")
