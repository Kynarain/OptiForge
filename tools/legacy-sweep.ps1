# Phase 1 (1.17-1.19.4) coverage: runs the remaining lines and builds through tools/legacy-rig-test.ps1 and
# prints one row per line. Each row is evidence from the dedicated server's log ("Dev joined the game"), the
# client's [OptiFine] line count and the crash-report count.
#
# Usage: pwsh -File tools/legacy-sweep.ps1 [-Only 1.19.4] [-Seconds 150]
[CmdletBinding()]
param(
    [string]$Only = '',
    [int]$Seconds = 150
)

$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent

# MC, Forge, edition, OptiFine version, port. Minecraft 1.17 is absent on purpose: Forge never shipped a 1.17\n# build (maven-metadata.xml only has 1.17.1-37.x), so that line cannot be tested through Forge at all. The bmclapi mirror lays builds out as /<mc>/<edition>/<version>,
# so previews use the edition that carries the build suffix (HD_U_G9) and releases just HD_U.
$lines = @(
    @{ Minecraft = '1.17.1'; Forge = '37.0.103'; Edition = 'HD_U';    Optifine = 'G9';    Port = 25581 }
    @{ Minecraft = '1.18';   Forge = '38.0.14';  Edition = 'HD_U';    Optifine = 'H3';    Port = 25582 }
    @{ Minecraft = '1.18.1'; Forge = '39.1.0';   Edition = 'HD_U';    Optifine = 'H6';    Port = 25583 }
    @{ Minecraft = '1.19';   Forge = '41.1.0';   Edition = 'HD_U';    Optifine = 'H9';    Port = 25584 }
    @{ Minecraft = '1.19.1'; Forge = '42.0.9';   Edition = 'HD_U';    Optifine = 'H9';    Port = 25585 }
    @{ Minecraft = '1.19.3'; Forge = '44.1.23';  Edition = 'HD_U';    Optifine = 'I3';    Port = 25586 }
    @{ Minecraft = '1.19.4'; Forge = '45.0.23';  Edition = 'HD_U';    Optifine = 'I3';    Port = 25587 }
)

$results = @()
foreach ($line in $lines) {
    if ($Only -and $line.Minecraft -ne $Only) { continue }
    Write-Host ("--- {0} + Forge {1} + OptiFine {2} {3}" -f $line.Minecraft, $line.Forge, $line.Edition, $line.Optifine)
    $out = & (Join-Path $PSScriptRoot 'legacy-rig-test.ps1') `
        -Minecraft $line.Minecraft -Forge $line.Forge -Optifine $line.Optifine -Edition $line.Edition `
        -Port $line.Port -Seconds $Seconds 2>&1
    # Read the evidence straight from the logs instead of scraping the child's stdout: Write-Host output does
    # not travel through the pipeline, and that made every row read False/-1 while the run itself was green.
    $gameDir = Join-Path $root "build\rig-instance-$($line.Minecraft)"
    $clientLog = Get-Content (Join-Path $gameDir 'logs\latest.log') -ErrorAction SilentlyContinue
    $serverLog = Get-Content (Join-Path 'I:\mods\optifineoforge-test' "downloads\forge-install-$($line.Minecraft)-$($line.Forge)\server\server-run.log") -ErrorAction SilentlyContinue
    $joined = [bool]($clientLog | Select-String -Pattern 'joined the game|logged in with entity id|Connecting to')
    $serverJoined = [bool]($serverLog | Select-String -Pattern 'joined the game|logged in with entity id')
    $crashes = (Get-ChildItem (Join-Path $gameDir 'crash-reports') -ErrorAction SilentlyContinue | Measure-Object).Count
    $optifine = ($clientLog | Select-String -Pattern '\[OptiFine\]' | Measure-Object).Count
    $results += [pscustomobject]@{
        Line = $line.Minecraft
        Forge = $line.Forge
        OptiFine = "$($line.Edition) $($line.Optifine)"
        ServerSawJoin = $serverJoined
        Crashes = $crashes
        OptiFineLines = $optifine
    }
    Write-Host ("  -> serverSawJoin={0} crashes={1} optifine={2}" -f $serverJoined, $crashes, $optifine)
}

Write-Host ''
Write-Host '==== SUMMARY ===='
$results | Format-Table -AutoSize | Out-String | Write-Host
$bad = $results | Where-Object { (-not $_.ServerSawJoin) -or $_.Crashes -gt 0 }
if ($bad) {
    Write-Host ('NOT GREEN: ' + (($bad | ForEach-Object { $_.Line }) -join ', '))
} else {
    Write-Host 'ALL GREEN'
}
