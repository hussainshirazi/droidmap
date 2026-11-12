# Installs droidmap on Windows: the jar from a GitHub release, and a `droidmap` command on your PATH.
#
#   irm https://raw.githubusercontent.com/hussainshirazi/droidmap/main/install.ps1 | iex
#
# $env:DROIDMAP_VERSION = "0.2.0" picks a release (default: the latest); $env:DROIDMAP_HOME
# (default %LOCALAPPDATA%\droidmap) is where it goes.
$ErrorActionPreference = 'Stop'

$version = $env:DROIDMAP_VERSION
$dir = if ($env:DROIDMAP_HOME) { $env:DROIDMAP_HOME } else { Join-Path $env:LOCALAPPDATA 'droidmap' }
$releases = 'https://github.com/hussainshirazi/droidmap/releases'
$url = if ($version) { "$releases/download/v$version" } else { "$releases/latest/download" }

New-Item -ItemType Directory -Force $dir | Out-Null
$jar = Join-Path $dir 'droidmap.jar'
Write-Host "Downloading $url/droidmap.jar"
Invoke-WebRequest "$url/droidmap.jar" -OutFile "$jar.part" -UseBasicParsing

# Releases since 0.2.0 publish a checksum; check it when there is one.
$expected = $null
try { $expected = ((Invoke-WebRequest "$url/droidmap.jar.sha256" -UseBasicParsing).Content -split '\s+')[0] } catch { }
if ($expected) {
    $actual = (Get-FileHash "$jar.part" -Algorithm SHA256).Hash
    if ($actual -ne $expected) {
        Remove-Item "$jar.part"
        throw 'droidmap: the download does not match its checksum; nothing was installed'
    }
}
Move-Item -Force "$jar.part" $jar

# droidmap.cmd runs the jar with Java 17 or newer: %JAVA_HOME%'s, the one on the PATH, or the one
# Android Studio brings (so a machine with Android Studio needs no Java setup).
# A short run starts faster with only the quick JIT compiler and the serial GC.
$cmd = @'
@echo off
setlocal
set "J="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "J=%JAVA_HOME%\bin\java.exe"
if not defined J for %%P in (java.exe) do set "J=%%~$PATH:P"
if not defined J if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "J=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if not defined J if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "J=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
if not defined J (
  echo droidmap needs Java 17 or newer: install Android Studio or a JDK, or set JAVA_HOME. 1>&2
  exit /b 2
)
"%J%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -jar "__JAR__" %*
'@
Set-Content -Path (Join-Path $dir 'droidmap.cmd') -Value $cmd.Replace('__JAR__', $jar) -Encoding ascii

$path = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (($path -split ';') -contains $dir)) {
    [Environment]::SetEnvironmentVariable('Path', ($path.TrimEnd(';') + ";$dir"), 'User')
    Write-Host "Added $dir to your PATH: open a new terminal to use droidmap."
}
$env:Path = "$env:Path;$dir"
Write-Host "Installed $(Join-Path $dir 'droidmap.cmd')"
& (Join-Path $dir 'droidmap.cmd') --version
