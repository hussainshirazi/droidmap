@echo off
rem Starts droidmap for the Claude Code plugin on Windows with Java 17 or newer: %DROIDMAP_JAVA%,
rem %JAVA_HOME%'s, the one on the PATH, or Android Studio's, so a machine with Android Studio needs no
rem Java setup. Droidmap.java downloads the matching droidmap release once.
setlocal
set "J="
if defined DROIDMAP_JAVA if exist "%DROIDMAP_JAVA%" set "J=%DROIDMAP_JAVA%"
if not defined J if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "J=%JAVA_HOME%\bin\java.exe"
if not defined J for %%P in (java.exe) do set "J=%%~$PATH:P"
if not defined J if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "J=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if not defined J if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "J=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
if not defined J (
  echo droidmap needs Java 17 or newer: install Android Studio or a JDK, or set JAVA_HOME ^(or DROIDMAP_JAVA^). 1>&2
  exit /b 2
)
"%J%" "%~dp0Droidmap.java" %*
