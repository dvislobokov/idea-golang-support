#!/usr/bin/env bash
# A copy of the Go playground for the WSL sandbox in ~/robot/go-playground (a Linux path: `go` and the IDE are slow on /mnt/c), without
# .idea; the GoLand probe files (tools/ui-robot/goland/probe) go to internal/probe as the parity checks expect. Modules are downloaded
# with the Go of WSL when there is one.
#   wsl -d Ubuntu -- bash tools/ui-robot/wsl/copy-playground.sh
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
target="$HOME/robot/go-playground"
rm -rf "$target"
mkdir -p "$target/internal/probe"
(cd "$repo/playground" && tar cf - --exclude=.idea .) | tar xf - -C "$target"
cp -r "$repo/tools/ui-robot/goland/probe/." "$target/internal/probe/"
for goroot in "$HOME/sdk/go" /usr/local/go; do [ -x "$goroot/bin/go" ] && export PATH="$goroot/bin:$PATH" && break; done
if command -v go >/dev/null; then (cd "$target" && go mod download 2>&1 | tail -2); else echo "no go in WSL: modules not downloaded" >&2; fi
echo "playground in $target"
