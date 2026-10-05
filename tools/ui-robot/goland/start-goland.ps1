# Starts GoLand as the reference IDE for the UI robot (docs/goland-analysis), isolated from the user's own GoLand: a copy of its config
# (license goland.key and options/go.sdk.xml included; the password store c.kdbx / c.pwd and plugins left out), its own system, plugins
# (only robot-server-plugin, taken from the plugin's UI-test sandbox) and logs under $Root. The user's GoLand config is only read.
#   powershell -File tools/ui-robot/goland/start-goland.ps1 [-Fresh]
# Prints the PID of goland64.exe: export GOLAND_PID=<pid>; . tools/ui-robot/goland/analysis/gj.sh (AppActivate needs it).
# Then: ROBOT_PORT=8595 ROBOT_CONFIG=goland-robot/config python tools/ui-robot/robot.py wait. Scripts run with `robot.py js` (not `script`: prelude.js wants our plugin).
#
# GoLand as the reference (there is no tools/ui-robot/README.md, so it is noted here):
# - one IDE at a time: GoLand on 8595, our sandbox on 8083 (dotnet's on 8082); close GoLand after the run - the port executes code in the IDE;
# - ROBOT_CONFIG makes robot.py accept an IDE other than our sandbox (check_sandbox matches it against the config path);
# - work on build/ui-robot/goland-playground (a copy of playground/ without .idea), probe files are in tools/ui-robot/goland/probe;
# - drivers: analysis/popups.sh, settings.sh, completions.sh (+ completions2.sh), typing.sh; pitfalls and results: docs/goland-analysis/README.md;
# - $Root keeps a copy of the license (goland.key): delete $Root when done with it.
param(
    [string]$GoLand = "C:\Program Files\JetBrains\GoLand 2026.2.3",
    [string]$Config = "GoLand2026.2",
    [string]$Root = "$env:USERPROFILE\goland-robot",
    [int]$Port = 8595,
    [string]$Project = "$PSScriptRoot\..\..\..\build\ui-robot\goland-playground",
    [switch]$Fresh
)
$ErrorActionPreference = "Stop"
$robot = Get-ChildItem "$PSScriptRoot\..\..\..\.intellijPlatform\sandbox" -Recurse -Directory -Filter robot-server-plugin | Select-Object -First 1
if ($null -eq $robot) { throw "robot-server-plugin not found: run ./gradlew.bat runIdeForUiTests --no-configuration-cache once" }
if ($Fresh -and (Test-Path $Root)) { Remove-Item -Recurse -Force $Root }
foreach ($dir in "config", "system", "plugins", "log") { New-Item -ItemType Directory -Force "$Root\$dir" | Out-Null }
if (-not (Test-Path "$Root\config\options")) {
    Get-ChildItem "$env:APPDATA\JetBrains\$Config" -Exclude c.kdbx, c.pwd, plugins | Copy-Item -Destination "$Root\config" -Recurse -Force
}
Copy-Item $robot.FullName "$Root\plugins" -Recurse -Force
$r = $Root -replace '\\', '/'
"idea.config.path=$r/config`nidea.system.path=$r/system`nidea.plugins.path=$r/plugins`nidea.log.path=$r/log" | Out-File -Encoding ascii "$Root\goland.properties"
$options = Get-Content "$GoLand\bin\goland64.exe.vmoptions"
$options += "-Drobot-server.port=$Port", "-Djb.privacy.policy.text=<!--999.999-->", "-Djb.consents.confirmation.enabled=false",
    "-Dide.show.tips.on.startup.default.value=false", "-Didea.trust.all.projects=true"
$options | Out-File -Encoding ascii "$Root\goland.vmoptions"
$env:GOLAND_PROPERTIES = "$Root\goland.properties"
$env:GOLAND_VM_OPTIONS = "$Root\goland.vmoptions"
$p = Start-Process -FilePath "$GoLand\bin\goland64.exe" -ArgumentList "`"$((Resolve-Path $Project).Path)`"" -PassThru
"GoLand started, PID $($p.Id), robot on 127.0.0.1:$Port"
