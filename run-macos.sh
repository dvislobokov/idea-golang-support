#!/usr/bin/env bash
# Build the plugin and install it into your real GIGA IDE, then restart GIGA normally. That is the single-process
# ("monolith") mode where the debugger works: delve starts, breakpoints can be set, frames and variables show.
#
# Why not `./gradlew runIde`: on GIGA IDE every dev sandbox — gradle runIde, or even the native launcher with a throwaway
# config — starts the IDE as a backend + embedded frontend (split / LUX). Its RPC descriptor registry is incomplete there,
# so `FrontendXBreakpointTypesManager` crashes (breakpoints cannot be placed, for any language) and the debugger UI is not
# built (`[Split debugger] RunContentDescriptor` SEVERE). `-Dide.frontend.split=false` does not turn it off (checked). The
# normally installed app runs monolithic and debugs fine, so this script installs the build into it — the same thing as
# doing Settings | Plugins | Install Plugin from Disk by hand, automated.
#
# Usage:
#   ./run-macos.sh                       # build, install, (re)launch GIGA
#   GIGA_APP="/Applications/Other.app" GIGA_PLUGINS="$HOME/Library/Application Support/Vendor/Prod/plugins" ./run-macos.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="${GIGA_APP:-/Applications/GIGA IDE 2026.1.app}"
PLUGINS="${GIGA_PLUGINS:-$HOME/Library/Application Support/GIGAIDE/GIGAIDE-2026.1/plugins}"
JBR="$APP/Contents/jbr/Contents/Home"
PLUGIN_DIR_NAME="idea-golang-support"

[ -d "$JBR" ]     || { echo "IDE JBR not found: $JBR (set GIGA_APP=/path/to/YourIDE.app)"; exit 1; }
[ -d "$PLUGINS" ] || { echo "User plugins dir not found: $PLUGINS (set GIGA_PLUGINS=...)"; exit 1; }

echo "==> Building the plugin with the JBR of $APP ..."
JAVA_HOME="$JBR" "$REPO/gradlew" -p "$REPO" buildPlugin -PlocalIdePath="$APP" -q

ZIP="$(ls -t "$REPO"/build/distributions/*.zip 2>/dev/null | head -1)"
[ -n "$ZIP" ] || { echo "No plugin zip in build/distributions"; exit 1; }
echo "==> Built: $ZIP"

echo "==> Installing into $PLUGINS ..."
rm -rf "$PLUGINS/$PLUGIN_DIR_NAME"
/usr/bin/unzip -q -o "$ZIP" -d "$PLUGINS"
[ -d "$PLUGINS/$PLUGIN_DIR_NAME" ] || { echo "Install failed: $PLUGINS/$PLUGIN_DIR_NAME not created"; exit 1; }

# A running IDE keeps the old build in memory; it must be fully quit and started again to load the new one.
if pgrep -f "$APP/Contents/MacOS/idea" >/dev/null 2>&1; then
  echo "==> GIGA IDE is running. Quit it fully (Cmd+Q) and start it again to load the new build."
else
  echo "==> Launching GIGA IDE ..."
  open -a "$APP"
fi
echo "Done. '$PLUGIN_DIR_NAME' installed for the normal (monolith) GIGA IDE — debugging works there."
