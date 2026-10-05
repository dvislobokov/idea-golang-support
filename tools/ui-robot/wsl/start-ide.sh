#!/usr/bin/env bash
# A sandbox IDE of the Go plugin inside WSL, on an invisible X screen (Xvfb), with the Remote Robot server: unlike the sandbox of
# `runIdeForUiTests` on the user's desktop, real mouse and keyboard input (xdotool), hovers and video (ffmpeg) disturb nobody.
# Modelled on ../idea-dotnet-support/tools/ui-robot/wsl (set up 2026-10-05); own port, display and sandbox root so both plugins can
# have their sandbox prepared — but run ONE IDE at a time: the machine's memory is shared (a WSL IDE killed a Gradle test run once).
#
#   tools/ui-robot/wsl/start-ide.sh <plugin.zip (Windows or WSL path)> [robot port, 8597] [display, 98]
#
# Run from Windows: `wsl -d Ubuntu -- bash tools/ui-robot/wsl/start-ide.sh build/distributions/idea-golang-support-<version>.zip`
# (from the repo root; the repo is reached as /mnt/c/...). The robot answers on 127.0.0.1:<port> inside WSL only (NAT): use robot.sh
# or `ROBOT_WSL=1 . tools/ui-robot/scripts/session.sh`. Stop with stop-ide.sh.
# Needs: IDEA for Linux in ~/ide (the build of `localIdePath`), a Go toolchain in ~/sdk/go or /usr/local/go (the plugin runs `go`),
# apt packages xvfb x11-utils xdotool ffmpeg (sudo once, by the user). `unzip` is not needed.
set -euo pipefail

zip="${1:?plugin zip}"
port="${2:-8597}"
display="${3:-98}"
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
case "$zip" in
    [A-Za-z]:*) zip="$(wslpath -u "$zip")" ;;
    /*) ;;
    *) zip="$repo/$zip" ;;
esac

ide="$(ls -d "$HOME"/ide/idea-IU-* | sort | tail -1)"
root="$HOME/robot/go-sandbox"
mkdir -p "$root"/{config,system,plugins,log}

# the plugin as built, and the robot server as Gradle downloaded it for runIdeForUiTests on Windows
rm -rf "$root/plugins/idea-golang-support"
python3 -m zipfile -e "$zip" "$root/plugins"
robot_plugin="$repo/.intellijPlatform/sandbox/idea-golang-support/IU-2026.1.4/plugins_runIdeForUiTests/robot-server-plugin"
[ -d "$robot_plugin" ] || { echo "no $robot_plugin: run ./gradlew.bat runIdeForUiTests once on Windows" >&2; exit 1; }
rm -rf "$root/plugins/robot-server-plugin"
cp -r "$robot_plugin" "$root/plugins/"

cat > "$root/idea.properties" <<EOF
idea.config.path=$root/config
idea.system.path=$root/system
idea.plugins.path=$root/plugins
idea.log.path=$root/log
EOF
{
    cat "$ide/bin/idea64.vmoptions"
    echo "-Xmx3g"
    # X11 on the Xvfb screen: with WSLg the IDE would pick Wayland (WLToolkit) and open on the user's desktop
    echo "-Dawt.toolkit.name=XToolkit"
    echo "-Drobot-server.port=$port"
    echo "-Djb.privacy.policy.text=<!--999.999-->"
    echo "-Djb.consents.confirmation.enabled=false"
    echo "-Dide.show.tips.on.startup.default.value=false"
    echo "-Didea.trust.all.projects=true"
} > "$root/idea.vmoptions"

if ! xdpyinfo -display ":$display" >/dev/null 2>&1; then
    Xvfb ":$display" -screen 0 1920x1080x24 -nolisten tcp > "$root/log/xvfb.log" 2>&1 &
    for _ in $(seq 1 50); do xdpyinfo -display ":$display" >/dev/null 2>&1 && break; sleep 0.1; done
fi

export DISPLAY=":$display"
unset WAYLAND_DISPLAY
export IDEA_PROPERTIES="$root/idea.properties"
export IDEA_VM_OPTIONS="$root/idea.vmoptions"
for goroot in "$HOME/sdk/go" /usr/local/go; do [ -x "$goroot/bin/go" ] && export PATH="$goroot/bin:$HOME/go/bin:$PATH" && break; done
command -v go >/dev/null || echo "warning: no go toolchain in WSL; the plugin falls back to its own project model" >&2
nohup "$ide/bin/idea" > "$root/log/ide-stdout.log" 2>&1 &
echo $! > "$root/ide.pid"
echo "IDE $(cat "$ide/build.txt") pid $(cat "$root/ide.pid") on :$display, robot on 127.0.0.1:$port (inside WSL); logs in $root/log"
