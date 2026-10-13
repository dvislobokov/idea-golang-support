#!/usr/bin/env bash
# GoLand side of docs/compare part 3 (tools/ui-robot/goland/analysis/refactor_probe.js) on a clean copy of the clone opened in GoLand
# (CLONE, default build/compare/prometheus-goland). Per scenario: Alt+Enter and Refactor This lists, and for rename / safe-delete /
# intention the refactoring itself, then git diff, go build ./..., go vet of the touched packages, and the copy restored.
#   GOLAND_PID=<pid> bash tools/compare/refactor/goland_run.sh [ID...]
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/goland/analysis/gj.sh 2>/dev/null
CLONE=${CLONE:-build/compare/prometheus-goland}
C=$(cd $CLONE && pwd -W)
OUT=build/compare/out/refactor-goland; mkdir -p $OUT
SC=$(cd tools/compare/refactor && pwd -W)/scenarios.json
if [ $# -gt 0 ]; then IDS=("$@"); else mapfile -t IDS < <(python -c "import json; [print(s['id']) for s in json.load(open('tools/compare/refactor/scenarios.json', encoding='utf-8'))['scenarios']]" | tr -d '\r'); fi
refresh() { cat > build/ga-refresh.js <<JS
com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
  var d = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("$C")
  com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, d)
  com.intellij.openapi.fileEditor.FileEditorManager.getInstance(com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]).getOpenFiles().forEach(function (f) {})
} }), com.intellij.openapi.application.ModalityState.any())
"@@@refreshed"
JS
  gj build/ga-refresh.js > /dev/null; }
restore() { git -C $CLONE checkout -q -- . ; git -C $CLONE clean -fdq -e .idea -e typingcmp; refresh; sleep 5; TMO=300 gj $A/wait_indexing.js "s|__MAX__|240|" > /dev/null; }
for id in "${IDS[@]}"; do
  kind=$(python -c "import json,sys; print([s['refactoring'] for s in json.load(open('tools/compare/refactor/scenarios.json', encoding='utf-8'))['scenarios'] if s['id']=='$id'][0])" | tr -d '\r')
  case $kind in rename|safe-delete|intention) DO=yes;; *) DO=no;; esac
  activate
  TMO=300 gj $A/refactor_probe.js "s|__SCENARIOS__|$SC|" "s|__DIR__|$C|" "s|__ID__|$id|" "s|__DO__|$DO|" "s|__SYNC__|${SYNC:-2500}|" > $OUT/$id.json
  if [ $DO = yes ]; then
    git -C $CLONE add -N -- . ':!.idea' ':!typingcmp' 2>/dev/null; git -C $CLONE diff -- . ':!.idea' > $OUT/$id.diff; git -C $CLONE reset -q
    pk=$(git -C $CLONE status --porcelain -- . ':!.idea' ':!typingcmp' | awk '{print $2}' | xargs -r -n1 dirname | sort -u | sed 's|^|./|' | tr '\n' ' ')
    (cd $CLONE && { go build ./... 2>&1 | head -30; echo "BUILD_EXIT=${PIPESTATUS[0]}"; if [ -n "$pk" ]; then go vet $pk 2>&1 | head -20; echo "VET_EXIT=${PIPESTATUS[0]}"; else echo "VET_EXIT=none"; fi; }) > $OUT/$id.build
    echo "$id ($kind): $(tail -c 300 $OUT/$id.json | tr -d '\n' | cut -c1-200) | $(grep -h "_EXIT" $OUT/$id.build | tr '\n' ' ')"
    restore
  else
    echo "$id ($kind): $(cut -c1-200 $OUT/$id.json)"
  fi
done
[ -z "$(git -C $CLONE status --porcelain | grep -v '^?? .idea')" ] && echo "copy clean" || git -C $CLONE status --short
