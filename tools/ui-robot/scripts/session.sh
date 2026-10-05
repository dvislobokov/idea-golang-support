# Helpers for robot-driven debugger checks; source it from Git Bash in the repository root:
#   . tools/ui-robot/scripts/session.sh
export PYTHONIOENCODING=utf-8
ROBOT="python tools/ui-robot/robot.py"
ROBOT_SCRIPTS="tools/ui-robot/scripts"
ROBOT_TMP="${TEMP:-/tmp}/ui-robot.js"
# ROBOT_WSL=1: the sandbox of tools/ui-robot/wsl (its robot is reached only by the Python of WSL; the script file must be readable there)
if [ "${ROBOT_WSL:-}" = "1" ]; then
    ROBOT="wsl -d Ubuntu -- bash tools/ui-robot/wsl/robot.sh"
    mkdir -p build/ui-robot
    ROBOT_TMP="build/ui-robot/robot.js"
    PLAYGROUND="/home/$USER/robot/go-playground"
fi

# curl to the robot must not go through the proxy of the shell either (robot.py ignores it by itself)
export NO_PROXY="127.0.0.1,localhost" no_proxy="127.0.0.1,localhost"
# The playground copy the sandbox works on (see CLAUDE.md), as the scripts want it: forward slashes
[ "${ROBOT_WSL:-}" = "1" ] || PLAYGROUND="$(pwd -W 2>/dev/null || pwd)/build/ui-robot/playground"

# robot_js FILE [sed-expression ...]: run a script with its __PLACEHOLDERS__ filled in, print what it returns.
# prelude.js goes first: `cls("io.github...")` and `kotlinObject(...)` load the classes of the plugin, which Rhino cannot see as `io.github...`.
# Pitfalls of Rhino (seen live): `const` inside a loop keeps its first value, use `var`; a double quote in a sed replacement must be `\\"`.
robot_js() {
    local file="$1"; shift
    local args=()
    for expression in "$@"; do args+=(-e "$expression"); done
    { cat "$ROBOT_SCRIPTS/prelude.js"; if [ ${#args[@]} -gt 0 ]; then sed "${args[@]}" "$ROBOT_SCRIPTS/$file"; else cat "$ROBOT_SCRIPTS/$file"; fi; } > "$ROBOT_TMP"
    $ROBOT js "$ROBOT_TMP" 2>&1 | tr -d '\000-\010' | sed 's/^[^A-Za-z_$<[(0-9]*t.\{0,2\}//'
}

# invoke ACTION_ID: performs an action with the data context of the frame (`robot.py action` fails when the focus owner is not showing)
invoke() { robot_js invoke_action.js "s|__ID__|$1|"; }
# setting NAME VALUE: a property of GoSettings by its Kotlin name, e.g. `setting DebugAnyGoVersion false`, `setting Formatter '"GOFMT"'`
setting() { robot_js settings.js "s|__NAME__|$1|" "s|__VALUE__|$2|"; }
toolwindow() { robot_js toolwindow.js "s|__ID__|$1|"; }
# openfile FILE [LINE]: a file of the playground copy, by its path inside the project
openfile() { $ROBOT openfile "$PLAYGROUND/$1" "${2:-1}"; }

state() { robot_js state.js | grep session; }

# wait_state SECONDS REGEX: poll until the session line matches
wait_state() {
    local line=""
    for _ in $(seq 1 $(( $1 / 2 ))); do
        ping -n 3 127.0.0.1 > /dev/null
        line=$(state)
        if echo "$line" | grep -qE "$2"; then break; fi
    done
    echo "$line"
}

evaluate() { robot_js evaluate.js "s|__EXPRESSION__|$1|" "s|__CHILDREN__|${2:-0}|"; }
set_value() { robot_js set_value.js "s|__NAME__|$1|" "s|__VALUE__|$2|"; }
stop_all() { robot_js stop_all.js > /dev/null; }
