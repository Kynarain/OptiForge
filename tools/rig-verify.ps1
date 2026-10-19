# Launches one rig instance with exactly one OptiForge build and a fresh pack cache.
#
# Two mistakes this exists to prevent, both measured:
#  - leaving older OptiForge-*.jar files in <game>/mods: Forge loads all of them and the older code wins,
#    so a "new" build can appear to run while an old one is actually executing;
#  - reusing <game>/optiforge/pack: the cache key hashes only the input jars, so a pack built by an older
#    OptiForge keeps being served and preparation-time fixes silently never run.
#
# Usage:
#   pwsh -File tools/rig-verify.ps1 -VersionId 1.21.7-forge-57.0.3 -GameDir build\rig-instance-1.21.7 `
#        -Rig I:\mods\optifineoforge-test -Jar build\libs\OptiForge-0.5.0.jar -World CaptureWorld [-Seconds 200]

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$VersionId,
    [Parameter(Mandatory = $true)][string]$GameDir,
    [Parameter(Mandatory = $true)][string]$Rig,
    [string]$Jar = '',
    [string]$World = '',
    [int]$Seconds = 180,
    [string]$JavaHome = '',
    [switch]$KeepPack
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Jar) {
    $props = Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^mod_version_base=' }
    $version = ($props -split '=')[1].Trim()
    $Jar = Join-Path $root "build\libs\OptiForge-$version.jar"
}
$Jar = (Resolve-Path $Jar).Path
if (-not (Test-Path $GameDir)) { New-Item -ItemType Directory -Force -Path $GameDir | Out-Null }
$mods = Join-Path $GameDir 'mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null

foreach ($stale in Get-ChildItem $mods -Filter 'OptiForge-*.jar' -ErrorAction SilentlyContinue) {
    if ($stale.FullName -ne $Jar) { Remove-Item $stale.FullName -Force }
}
Copy-Item $Jar $mods -Force

if (-not $KeepPack) { Remove-Item (Join-Path $GameDir 'optiforge\pack') -Recurse -Force -ErrorAction SilentlyContinue }
Remove-Item (Join-Path $GameDir 'crash-reports') -Recurse -Force -ErrorAction SilentlyContinue
if ($World) { Remove-Item (Join-Path $GameDir "saves\$World\session.lock") -Force -ErrorAction SilentlyContinue }
$optionsTxt = Join-Path $GameDir 'options.txt'
if (-not (Test-Path $optionsTxt)) {
    [System.IO.File]::WriteAllText($optionsTxt, "enableVsync:false`r`n", (New-Object System.Text.UTF8Encoding($false)))
}

$env:RIG_EXTRA_GAME_ARGS = if ($World) { "--quickPlaySingleplayer=$World" } else { '' }
$env:RIG_EXTRA_JVM = "-Doptiforge.libraries=$(Join-Path $Rig 'libraries')"

$launch = Join-Path $Rig 'launch.ps1'
$arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launch, '-Rig', $Rig, '-VersionId', $VersionId,
    '-GameDir', (Resolve-Path $GameDir).Path, '-Mods', $Jar, '-Seconds', $Seconds)
if ($JavaHome) { $arguments += @('-JavaHome', $JavaHome) }
& powershell @arguments 2>&1 | Select-Object -Last 10

# summarise from the game's own log, which is the evidence that matters
$log = Join-Path $GameDir 'logs\latest.log'
if (Test-Path $log) {
    $text = Get-Content $log
    "joined the game     : " + [bool]($text | Select-String -Pattern 'joined the game')
    "optifine lines      : " + ($text | Select-String -Pattern '\[OptiFine\]' | Measure-Object).Count
    "forge version (OF)  : " + (($text | Select-String -Pattern '\[OptiFine\] Forge version:' | Select-Object -Last 1) -replace '^.*Forge version:', '').Trim()
}
"crash reports       : " + (Get-ChildItem (Join-Path $GameDir 'crash-reports') -ErrorAction SilentlyContinue | Measure-Object).Count
"pack                : " + ((Get-ChildItem (Join-Path $GameDir 'optiforge\pack') -Directory -ErrorAction SilentlyContinue).Name -join ', ')
"mods                : " + ((Get-ChildItem $mods -File | Where-Object { $_.Extension -eq '.jar' }).Name -join ', ')
