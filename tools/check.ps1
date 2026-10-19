# Verifies that Forge will accept the built jar as a transformation service, without launching a game.
#
# Two things are checked, both offline:
#   1. the jar's module descriptor (what SecureJar builds, and what Forge's ModDirTransformerDiscoverer
#      actually reads) declares cpw.mods.modlauncher.api.ITransformationService;
#   2. ServiceLoader can load the class behind that declaration.
#
# --add-opens is required by securejarhandler (it reflects into java.lang.invoke.MethodHandles.Lookup);
# ModLauncher passes the same flag when it launches.
#
# Usage: pwsh -File tools/check.ps1 [-Jdk <path>]

[CmdletBinding()]
param(
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }),
    [string]$Jar = ''
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$deps = Join-Path $root 'build\deps'
$probeOut = Join-Path $root 'build\probe\serviceprobe'
$jarPath = if ($Jar) { (Resolve-Path $Jar).Path } else {
    $built = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'OptiForge-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $built) { throw 'no jar in build/libs - run tools/build.ps1 first' }
    $built.FullName
}

if (-not (Test-Path (Join-Path $deps 'modlauncher-10.0.9.jar'))) {
    & (Join-Path $PSScriptRoot 'fetch-deps.ps1')
}

New-Item -ItemType Directory -Force -Path $probeOut | Out-Null
$classpath = ((Get-ChildItem $deps -Filter '*.jar' | ForEach-Object { $_.FullName }) + $jarPath) -join ';'

& (Join-Path $Jdk 'bin\javac.exe') -cp $classpath -d $probeOut (Join-Path $PSScriptRoot 'ServiceProbe.java')
if ($LASTEXITCODE -ne 0) { throw "javac of ServiceProbe failed ($LASTEXITCODE)" }

& (Join-Path $Jdk 'bin\java.exe') --add-opens java.base/java.lang.invoke=ALL-UNNAMED `
    -cp "$classpath;$probeOut" ServiceProbe $jarPath
if ($LASTEXITCODE -ne 0) { throw "service probe FAILED for $jarPath" }
