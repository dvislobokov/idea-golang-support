@ECHO OFF
:: The plugin's Go and go.mod inspections over a project without the IDE UI, as a SARIF 2.1.0 report (docs\CI.md).
::
::   tools\ci\go-inspect.cmd <projectDir> <out.sarif> [--inspections A,B] [--min-severity weak|warning|error]
::
:: IDE_HOME         the IDE installation (the directory with bin\), required
:: GO_PLUGIN        the plugin ZIP: installed into a throwaway config, so the run never touches a running IDE;
::                  without it the IDE's own config and plugins are used (close the IDE first)
:: GO_INSPECT_WORK  config, system and logs of the run (default: %TEMP%\go-inspect-<random>)
::
:: Exit code: 0 no findings, 1 findings at or above --min-severity, 2 bad arguments or a failed run.
:: bin\idea.bat loses the exit code of the JVM (it ends with DEL), so the plugin writes it to GO_INSPECT_EXIT_CODE_FILE.
SETLOCAL
IF "%IDE_HOME%"=="" (
  ECHO go-inspect: set IDE_HOME to the IDE installation 1>&2
  EXIT /B 2
)
IF "%GO_INSPECT_WORK%"=="" SET "GO_INSPECT_WORK=%TEMP%\go-inspect-%RANDOM%%RANDOM%"
IF NOT EXIST "%GO_INSPECT_WORK%" MKDIR "%GO_INSPECT_WORK%"
:: System32 bsdtar: a GNU tar earlier on PATH (Git Bash) takes "C:" for a remote host
IF NOT "%GO_PLUGIN%"=="" (
  IF NOT EXIST "%GO_INSPECT_WORK%\config\plugins" MKDIR "%GO_INSPECT_WORK%\config\plugins"
  "%SystemRoot%\System32\tar.exe" -xf "%GO_PLUGIN%" -C "%GO_INSPECT_WORK%\config\plugins" || EXIT /B 2
  CALL :properties
)
SET "LAUNCHER="
FOR %%N IN (idea pycharm webstorm rider phpstorm clion rustrover rubymine datagrip) DO (
  IF NOT DEFINED LAUNCHER IF EXIST "%IDE_HOME%\bin\%%N.bat" SET "LAUNCHER=%IDE_HOME%\bin\%%N.bat"
)
IF NOT DEFINED LAUNCHER (
  ECHO go-inspect: no IDE launcher in %IDE_HOME%\bin 1>&2
  EXIT /B 2
)
SET "GO_INSPECT_EXIT_CODE_FILE=%GO_INSPECT_WORK%\exit-code"
IF EXIST "%GO_INSPECT_EXIT_CODE_FILE%" DEL "%GO_INSPECT_EXIT_CODE_FILE%"
CALL "%LAUNCHER%" go-inspect %*
IF NOT EXIST "%GO_INSPECT_EXIT_CODE_FILE%" (
  ECHO go-inspect: the IDE exited without a result, logs: %GO_INSPECT_WORK%\log 1>&2
  EXIT /B 2
)
SET /P CODE=<"%GO_INSPECT_EXIT_CODE_FILE%"
EXIT /B %CODE%

:properties
:: .properties treat a backslash as an escape: forward slashes
SET "W=%GO_INSPECT_WORK:\=/%"
(
  ECHO idea.config.path=%W%/config
  ECHO idea.system.path=%W%/system
  ECHO idea.log.path=%W%/log
) > "%GO_INSPECT_WORK%\idea.properties"
SET "IDEA_PROPERTIES=%GO_INSPECT_WORK%\idea.properties"
SET "PYCHARM_PROPERTIES=%GO_INSPECT_WORK%\idea.properties"
SET "WEBIDE_PROPERTIES=%GO_INSPECT_WORK%\idea.properties"
EXIT /B 0
