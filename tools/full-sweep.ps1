# Runs every verified line through OptiForge and prints one row per line: the same evidence the project records
# (joined a world, crash reports, [OptiFine] lines, pack key). Existing lines use their saved worlds through
# --quickPlaySingleplayer; the 1.17-1.19.4 lines are covered by tools/legacy-rig-test.ps1 instead, because those
# clients still accept --server/--port and Forge never implemented quickPlay for them.
#
# Usage: pwsh -File tools/full-sweep.ps1 [-Build] [-Only 1.21.7] [-Seconds 170]
[CmdletBinding()]
param(
    [switch]$Build,
    [string]$Only = '',
    [int]$Seconds = 170
)

$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$rig = 'I:\mods\optifineoforge-test'

if ($Build) {
    & (Join-Path $PSScriptRoot 'build.ps1') | Select-Object -Last 1
}

$jar = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'OptiForge-*.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Host "jar under test: $($jar.Name)"

# QuickPlay lines: version id, instance, world, and the JDK that line needs.
$lines = @(
    @{ Name = '1.20.1';  Id = '1.20.1-forge-47.4.20';  Dir = 'build\rig-instance-1.20.1-4720'; World = 'ForgeWorld';  Java = '' }
    @{ Name = '1.20.6';  Id = '1.20.6-forge-50.2.14';  Dir = 'build\rig-instance-1.20.6';       World = 'CaptureWorld'; Java = '' }
    @{ Name = '1.21';    Id = '1.21-forge-51.0.33';    Dir = 'build\verify-1.21';               World = 'RigSession';   Java = '' }
    @{ Name = '1.21.1';  Id = '1.21.1-forge-52.1.20';  Dir = 'build\rig-instance-1.21.1';       World = 'CaptureWorld'; Java = '' }
    @{ Name = '1.21.6';  Id = '1.21.6-forge-56.0.9';   Dir = 'build\rig-instance-1.21.6';       World = 'RigSession';   Java = '' }
    @{ Name = '1.21.7';  Id = '1.21.7-forge-57.0.3';   Dir = 'build\rig-instance-1.21.7';       World = 'CaptureWorld'; Java = '' }
    @{ Name = '1.21.8';  Id = '1.21.8-forge-58.1.26';  Dir = 'build\rig-instance-1.21.8';       World = 'CaptureWorld'; Java = '' }
    @{ Name = '1.21.9';  Id = '1.21.9-forge-59.0.5';   Dir = 'build\rig-instance-1.21.9';       World = 'CaptureWorld'; Java = '' }
    @{ Name = '1.21.10'; Id = '1.21.10-forge-60.1.18'; Dir = 'build\rig-instance-1.21.10';      World = 'CaptureWorld'; Java = '' }
    @{ Name = '1.21.11'; Id = '1.21.11-forge-61.2.4';  Dir = 'build\rig-instance-1.21.11';      World = 'CaptureWorld'; Java = '' }
    @{ Name = '26.1.2';  Id = '26.1.2-forge-64.1.5';   Dir = 'build\rig-instance-26.1.2';       World = 'ForgeWorld';   Java = (Join-Path $root 'build\jdk25') }
    @{ Name = '26.2';    Id = '26.2-forge-65.1.5';     Dir = 'build\rig-instance-26.2';         World = 'ForgeWorld';   Java = (Join-Path $root 'build\jdk25') }
)

$results = @()
foreach ($line in $lines) {
    if ($Only -and $line.Name -ne $Only) { continue }
    $gameDir = Join-Path $root $line.Dir
    if (-not (Test-Path $gameDir)) { Write-Host "SKIP $($line.Name): no instance $gameDir"; continue }
    Get-ChildItem (Join-Path $gameDir 'mods') -Filter '*-classes.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem (Join-Path $gameDir 'mods') -Filter 'OptiForge-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jar.FullName (Join-Path $gameDir 'mods') -Force
    Remove-Item (Join-Path $gameDir 'crash-reports') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $gameDir "saves\$($line.World)\session.lock") -Force -ErrorAction SilentlyContinue

    $verifyArgs = @{
        VersionId = $line.Id
        GameDir   = $gameDir
        Rig       = $rig
        World     = $line.World
        Seconds   = $Seconds
        Jar       = $jar.FullName
    }
    if ($line.Java) { $verifyArgs.JavaHome = $line.Java }
    $out = & (Join-Path $PSScriptRoot 'rig-verify.ps1') @verifyArgs 2>&1
    $log = Get-Content (Join-Path $gameDir 'logs\latest.log') -ErrorAction SilentlyContinue
    $joined = [bool]($log | Select-String -Pattern 'joined the game|logged in with entity id')
    $crashes = (Get-ChildItem (Join-Path $gameDir 'crash-reports') -ErrorAction SilentlyContinue | Measure-Object).Count
    $optifine = ($log | Select-String -Pattern '\[OptiFine\]' | Measure-Object).Count
    $pack = ($out | Select-String -Pattern '^pack\s*:' | Select-Object -First 1)
    $results += [pscustomobject]@{
        Line    = $line.Name
        Joined  = $joined
        Crashes = $crashes
        OptiFine = $optifine
        Pack    = if ($pack) { ($pack.Line -split ':', 2)[1].Trim() } else { '?' }
    }
    $last = $results[-1]
    Write-Host ("{0,-7} joined={1,-5} crashes={2} optifine={3}" -f $last.Line, $last.Joined, $last.Crashes, $last.OptiFine)
}

""
Write-Host "==== SUMMARY ===="
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = $results | Where-Object { (-not $_.Joined) -or $_.Crashes -gt 0 }
if ($failed) {
    Write-Host ("NOT GREEN: " + (($failed | ForEach-Object { $_.Line }) -join ', '))
} else {
    Write-Host 'ALL GREEN'
}
