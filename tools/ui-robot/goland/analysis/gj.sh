# Helpers for the GoLand analysis (docs/goland-analysis/README.md). Source from Git Bash in the repository root after start-goland.ps1:
#   export GOLAND_PID=<pid printed by start-goland.ps1>; . tools/ui-robot/goland/analysis/gj.sh
# Scripts go through `robot.py js` (plain Rhino): `robot.py script` glues prelude.js, which looks for our plugin, absent in GoLand.
# ROBOT_CONFIG: robot.py refuses IDEs other than the plugin sandbox unless their config path contains this
# TARGET=plugin: the same probes on our sandbox (runIdeForUiTests, port 8083, playground build/ui-robot/playground); GOLAND_PID is then the sandbox java PID
if [ "$TARGET" = plugin ]; then
  export ROBOT_PORT=8083 NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8; unset ROBOT_CONFIG; PG=build/ui-robot/playground
else
  export ROBOT_PORT=8595 NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8 ROBOT_CONFIG=goland-robot/config; PG=build/ui-robot/goland-playground
fi
R=$(git rev-parse --show-toplevel)
A=$R/tools/ui-robot/goland/analysis
IMG=$R/docs/goland-analysis/img
# gj FILE [sed-expr...]: runs a script with placeholders filled in, prints what it returns (scripts prefix their result with @@@)
gj() {
  local file="$1"; shift
  local args=()
  for e in "$@"; do args+=(-e "$e"); done
  if [ ${#args[@]} -gt 0 ]; then sed "${args[@]}" "$file" > $R/build/ga-tmp.js; else cp "$file" $R/build/ga-tmp.js; fi
  timeout ${TMO:-150} python $R/tools/ui-robot/robot.py js $R/build/ga-tmp.js 2>&1 | tr -d '\000-\010' | sed '1s/^.*@@@//'
}
# Popups and completion need the GoLand window to be the foreground window (Windows refuses focusProjectWindow otherwise)
activate() { powershell -NoProfile -Command "\$ws = New-Object -ComObject WScript.Shell; [void]\$ws.AppActivate($GOLAND_PID)"; }
# Escape closes Alt+Enter-like popups cleanly; closing them by moving the caret froze the EDT once in Rider
escape() { powershell -NoProfile -Command "\$ws = New-Object -ComObject WScript.Shell; [void]\$ws.AppActivate($GOLAND_PID); Start-Sleep -Milliseconds 300; \$ws.SendKeys('{ESC}')"; }
P=$(cd $R/$PG && pwd -W)
. $A/esc.sh
# comp MARK TYPE TYPED KIND [PICK] [LIMIT] [FILE]: completion probe (go_complete.js); FILE is relative to the playground
comp() {
  local f="$P/${7:-internal/probe/analysis.go}"
  gj $A/go_complete.js "s|__FILE__|$f|" "s|__MARK__|$(esc "$1")|" "s|__TYPE__|$(esc "$2")|" "s|__TYPED__|$(esc "$3")|" "s|__KIND__|$4|" "s|__PICK__|$(esc "${5:-}")|" "s|__LIMIT__|${6:-15}|" "s|__SYNC__|${SYNC:-1200}|" "s|__WAIT__|${WAIT:-4000}|" "s|__CHAR__|${CHAR:-n}|" "s|__AFTER__|${AFTER:-1500}|" "s|__SHOW__|${SHOW:-4}|" "s|__TIME__|${TIME:-1}|" "s|__KEEP__|${KEEP:-no}|" "s|__HEAD__|${HEAD:-0}|"
}
# pop FILE ANCHOR OFFSET ACTION [SHOTNAME]: caret at ANCHOR+OFFSET, action (tryToExecute), dump the popup list, screenshot, Escape
pop() {
  activate
  echo "######## $4 at «$2» ($1)"
  gj $A/popup.js "s|__FILE__|$P/$1|" "s|__AT__|$(esc "$2")|" "s|__OFF__|$3|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|80|" "s|__CLOSE__|no|"
  sleep 2
  gj $A/poll.js "s|__ACTION__|$4|" > /dev/null
  gj $A/popup.js "s|__FILE__||" "s|__AT__||" "s|__OFF__|0|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|80|" "s|__CLOSE__|no|" | sed -e "s/&#39;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e 's/&amp;/\&/g'
  if [ -n "$5" ]; then python $R/tools/ui-robot/robot.py shot "$IMG/$5" "//div[@class='HeavyWeightWindow'][.//div[@class='MyList']]" >/dev/null 2>&1; fi
  escape; sleep 1
}
