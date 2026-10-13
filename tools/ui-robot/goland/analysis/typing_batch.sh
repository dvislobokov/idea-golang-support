#!/usr/bin/env bash
# The typing / keyboard batch (tools/compare/typing/scenarios.txt) against GoLand, then the table plugin vs GoLand.
# Usage (Git Bash, repository root, GoLand started by start-goland.ps1 with its playground open, GOLAND_PID exported):
#   bash tools/ui-robot/goland/analysis/typing_batch.sh [ID...]
# robot.py js gives a script 180 s, so the scenarios go in chunks of CHUNK (default 8) ids, one result file per chunk in
# build/compare/out/typing-goland/; compare.py merges the directory. The plugin side: GO_HARNESS=1 gradlew :test --tests "*GoTypingHarnessTest*".
# TARGET=plugin (gj.sh) runs the same batch on the plugin's sandbox instead (port 8083), into build/compare/out/typing-sandbox/.
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/goland/analysis/gj.sh
python tools/compare/typing/to_json.py || exit 1
P=${PG_DIR:-$P}  # the project to work in: the playground by default, PG_DIR to use another open project
OUTDIR=$R/build/compare/out/typing-${TARGET:-goland}
[ "$TARGET" = plugin ] && OUTDIR=$R/build/compare/out/typing-sandbox
mkdir -p "$OUTDIR"
GR=$(go env GOROOT | tr '\\' '/')
if [ $# -gt 0 ]; then IDS=("$@"); else mapfile -t IDS < <(python -c "import json; [print(s['id']) for s in json.load(open('tools/compare/typing/scenarios.json', encoding='utf-8'))]"); fi
CHUNK=${CHUNK:-8}
activate
for ((i = 0; i < ${#IDS[@]}; i += CHUNK)); do
  part=("${IDS[@]:i:CHUNK}")
  only=$(IFS=,; echo "${part[*]}" | tr -d '\r')
  out="$OUTDIR/$(printf '%03d' $i).json"
  echo "=== chunk $i: $only"
  TMO=200 gj $A/typing_batch.js "s|__SCENARIOS__|$R/tools/compare/typing/scenarios.json|" "s|__DIR__|$P|" "s|__OUT__|$out|" "s|__ONLY__|$only|" \
    "s|__GOROOT__|$GR|" "s|__SYNC__|${SYNC:-1500}|" "s|__PAUSE__|${PAUSE:-150}|" "s|__SLOW__|${SLOW:-3000}|"
done
python tools/compare/typing/compare.py build/compare/out/typing.json "$OUTDIR" --details > "$OUTDIR.md"
echo "table: $OUTDIR.md"
