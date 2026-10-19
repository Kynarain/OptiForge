# Builds build/libs/OptiForge-<version>.jar with javac + jar, without Gradle.
#
# Gradle (build.gradle) is the canonical build, but it needs a 130 MB distribution download on a
# first run; this script is the same thing in ten lines and is what the repository's own checks run.
#
# Usage: pwsh -File tools/build.ps1 [-Jdk <path>]

[CmdletBinding()]
param(
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }),
    [string]$Version = '0.1.0'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$deps = Join-Path $root 'build\deps'
$classes = Join-Path $root 'build\classes'
$libs = Join-Path $root 'build\libs'

if (-not (Test-Path (Join-Path $deps 'modlauncher-10.0.9.jar'))) {
    & (Join-Path $PSScriptRoot 'fetch-deps.ps1')
}

$javac = Join-Path $Jdk 'bin\javac.exe'
$jarTool = Join-Path $Jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "javac not found under $Jdk (pass -Jdk)" }

Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libs | Out-Null

# Compile against ModLauncher/ASM only, targeting Java 17 so one jar loads on every line
# (1.20.x runs Java 17, 1.21.x 21, 26.x 25 - class file 61 is accepted by all of them).
$classpath = (Get-ChildItem $deps -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

Write-Host "javac $($sources.Count) file(s) with $Jdk"
& $javac -encoding UTF-8 --release 17 -Xlint:-options -cp $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Copy-Item (Join-Path $root 'src\main\resources\*') $classes -Recurse -Force

$out = Join-Path $libs "OptiForge-$Version.jar"
& $jarTool --create --file $out --main-class kynarain.cn.optiforge.Diagnose -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host ("built {0} ({1} bytes)" -f $out, (Get-Item $out).Length)
