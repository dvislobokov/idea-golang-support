#!/usr/bin/env bash
# Settings pages of GoLand: options of each page (settings_page.js), pictures of the main ones. Usage (GOLAND_PID exported):
#   bash tools/ui-robot/goland/analysis/settings.sh > docs/goland-analysis/dumps/settings-pages.txt
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/goland/analysis/gj.sh
sp() {
  echo; echo "################ $1 ($2)"
  activate
  gj $A/settings_page.js "s|__MODE__|open|" "s|__ID__|$2|" "s|__TABS__|no|" > /dev/null
  sleep ${WAITS:-6}
  gj $A/settings_page.js "s|__MODE__|dump|" "s|__ID__||" "s|__TABS__|${TABS:-no}|"
  if [ -n "$3" ]; then python $R/tools/ui-robot/robot.py shot "$IMG/$3" "//div[contains(@class,'FloatDialog') or @class='MyDialog']" > /dev/null; fi
  gj $A/settings_page.js "s|__MODE__|close|" "s|__ID__||" "s|__TABS__|no|" > /dev/null
  sleep 1
}
sp "Go" "go" ""
sp "Go | GOROOT" "go.sdk" 40-settings-goroot.png
sp "Go | GOPATH" "com.goide.configuration.GoLibrariesConfigurableProvider" ""
sp "Go | Go Modules" "go.vgo" 41-settings-go-modules.png
sp "Go | Build Tags" "com.goide.configuration.GoProjectSettingsConfigurable" 42-settings-build-tags.png
sp "Go | Formatting Functions" "go.custom.fmt.functions" ""
sp "Go | Imports" "go.autoimport" 43-settings-imports.png
sp "Go | Linters" "preference.GoLinterConfigurable" 44-settings-linters.png
sp "Editor | Inlay Hints" "inlay.hints" 45-settings-inlay-hints.png
sp "Editor | General | Code Completion" "editor.preferences.completion" ""
sp "Editor | General | Auto Import" "editor.preferences.import" ""
sp "Editor | General | Smart Keys" "editor.preferences.smartKeys" 46-settings-smart-keys.png
sp "Editor | General | Postfix Completion" "reference.settingsdialog.IDE.editor.postfix.templates" ""
sp "Editor | General | Gutter Icons" "editor.preferences.gutterIcons" ""
sp "Editor | General | Code Folding" "editor.preferences.folding" ""
sp "Editor | Intentions" "preferences.intentionPowerPack" ""
sp "Tools | Actions on Save" "actions.on.save" 47-settings-actions-on-save.png
sp "Build, Execution, Deployment | Debugger | Data Views | Go" "dlv.dataViews" ""
sp "Build, Execution, Deployment | Profilers | Go Profiler: Applications" "go.profiler.settings" ""
sp "Editor | Color Scheme | Go" "reference.settingsdialog.IDE.editor.colors.com.goide.highlighting.GoColorsAndFontsPage" 48-settings-color-scheme-go.png
