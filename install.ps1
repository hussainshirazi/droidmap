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

# droidmap.cmd runs the jar with %JAVA_HOME%'s java, or the one on the PATH (Java 17 or newer).
# A short run starts faster with only the quick JIT compiler and the serial GC.
$cmd = @"
@echo off
set "DROIDMAP_JAVA=java"
if defined JAVA_HOME set "DROIDMAP_JAVA=%JAVA_HOME%\bin\java"
"%DROIDMAP_JAVA%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -jar "$jar" %*
"@
Set-Content -Path (Join-Path $dir 'droidmap.cmd') -Value $cmd -Encoding ascii

$path = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (($path -split ';') -contains $dir)) {
    [Environment]::SetEnvironmentVariable('Path', ($path.TrimEnd(';') + ";$dir"), 'User')
    Write-Host "Added $dir to your PATH: open a new terminal to use droidmap."
}
$env:Path = "$env:Path;$dir"
Write-Host "Installed $(Join-Path $dir 'droidmap.cmd')"
& (Join-Path $dir 'droidmap.cmd') --version
