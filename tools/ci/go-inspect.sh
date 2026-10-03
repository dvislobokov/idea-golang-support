#!/usr/bin/env bash
# The plugin's Go and go.mod inspections over a project without the IDE UI, as a SARIF 2.1.0 report (docs/CI.md).
#
#   tools/ci/go-inspect.sh <projectDir> <out.sarif> [--inspections A,B] [--min-severity weak|warning|error]
#
# IDE_HOME         the IDE installation (the directory with bin/), required
# GO_PLUGIN        the plugin ZIP (build/distributions/*.zip) or an unpacked plugin directory: installed into a throwaway config,
#                  so the run never touches (nor waits for) a running IDE; without it the IDE's own config and plugins are used
# GO_INSPECT_WORK  config, system and logs of the run (default: a temporary directory, kept for the logs)
#
# Exit code: 0 no findings, 1 findings at or above --min-severity, 2 bad arguments or a failed run.
set -euo pipefail
: "${IDE_HOME:?set IDE_HOME to the IDE installation}"
work="${GO_INSPECT_WORK:-$(mktemp -d -t go-inspect.XXXXXX)}"
mkdir -p "$work"
if [ -n "${GO_PLUGIN:-}" ]; then
  mkdir -p "$work/config/plugins" "$work/system" "$work/log"
  if [ -d "$GO_PLUGIN" ]; then cp -R "$GO_PLUGIN" "$work/config/plugins/"; else unzip -q -o "$GO_PLUGIN" -d "$work/config/plugins"; fi
  printf 'idea.config.path=%s/config\nidea.system.path=%s/system\nidea.log.path=%s/log\n' "$work" "$work" "$work" > "$work/idea.properties"
  # each product reads its own variable; the first launcher found below decides which one matters
  export IDEA_PROPERTIES="$work/idea.properties" PYCHARM_PROPERTIES="$work/idea.properties" WEBIDE_PROPERTIES="$work/idea.properties"
fi
launcher=""
for name in idea pycharm webstorm rider phpstorm clion rustrover rubymine datagrip; do
  if [ -x "$IDE_HOME/bin/$name.sh" ]; then launcher="$IDE_HOME/bin/$name.sh"; break; fi
done
[ -n "$launcher" ] || { echo "go-inspect: no IDE launcher in $IDE_HOME/bin" >&2; exit 2; }
export GO_INSPECT_EXIT_CODE_FILE="$work/exit-code"
rm -f "$GO_INSPECT_EXIT_CODE_FILE"
"$launcher" go-inspect "$@" || true
if [ -s "$GO_INSPECT_EXIT_CODE_FILE" ]; then exit "$(cat "$GO_INSPECT_EXIT_CODE_FILE")"; fi
echo "go-inspect: the IDE exited without a result, logs: ${work}/log" >&2
exit 2
