# Regenerates data/optifine-forge-matrix.csv from BMCLAPI's OptiFine mirror.
#
# Why BMCLAPI and not optifine.net directly: the mirror carries the same "Forge" column as
# https://optifine.net/downloads (BMCLAPI scrapes it), but exposes it as JSON for every build it
# knows about, while the downloads page only renders the builds it currently lists. The column is
# also what HMCL uses as its OptiFine+Forge compatibility gate (HMCL-dev/HMCL#2063), so the values
# are cross-checked by a second consumer.
#
# Columns: mcversion,filename,type,patch,forge,forge_ok,forge_version
#   forge        raw cell, e.g. "Forge 47.2.18" / "Forge #2847" / "Forge N/A" / ""
#   forge_ok     true = OptiFine states Forge compatibility, false = states INcompatible,
#                ""    = the mirror has no value (build never listed) -> unknown, not "compatible"
#   forge_version  numeric part, "#" stripped ("#2847" -> "2847"); "" when unknown
#
# Usage: pwsh -File tools/build-matrix.ps1 [-OutFile data/optifine-forge-matrix.csv]

[CmdletBinding()]
param(
    [string]$OutFile = (Join-Path $PSScriptRoot '..\data\optifine-forge-matrix.csv')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# mirror versions that predate the versionlist endpoint (it starts at 1.13.2)
$OlderVersions = @('1.12.2', '1.12.1', '1.12', '1.11.2', '1.11', '1.10.2', '1.10',
                   '1.9.4', '1.9.0', '1.9.2', '1.8.9', '1.8.8', '1.8.0', '1.7.10', '1.7.2')

function Get-Builds([string]$url) {
    $raw = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 60).Content
    if ([string]::IsNullOrWhiteSpace($raw) -or $raw.Trim() -eq '[]') { return @() }
    # Assign before wrapping: @($raw | ConvertFrom-Json) would nest the whole array as one element
    # in Windows PowerShell 5.1, because the pipeline emits the JSON array as a single object.
    $parsed = $raw | ConvertFrom-Json
    return @($parsed)
}

Write-Host 'fetching /optifine/versionlist ...'
$all = @(Get-Builds 'https://bmclapi2.bangbang93.com/optifine/versionlist')
Write-Host ("  {0} builds, versions {1}.." -f $all.Count, ($all | Select-Object -First 1).mcversion)

foreach ($v in $OlderVersions) {
    try {
        $extra = @(Get-Builds "https://bmclapi2.bangbang93.com/optifine/$v")
        if ($extra.Count -gt 0) {
            Write-Host ("  + {0}: {1} builds (pre-1.13, not in versionlist)" -f $v, $extra.Count)
            $all += $extra
        }
    } catch {
        Write-Warning "  $v : $($_.Exception.Message)"
    }
}

function Get-ForgeOk([string]$cell) {
    if ([string]::IsNullOrWhiteSpace($cell)) { return '' }
    if ($cell -eq 'Forge N/A') { return 'false' }
    if ($cell -match '^Forge\s') { return 'true' }
    return ''
}

function Get-ForgeVersion([string]$cell) {
    if ([string]::IsNullOrWhiteSpace($cell)) { return '' }
    if ($cell -eq 'Forge N/A') { return '' }
    $v = ($cell -replace '^Forge\s*', '').Trim()
    $v = $v -replace '^#', ''
    return $v
}

function Esc([string]$s) {
    if ([string]::IsNullOrEmpty($s)) { return '' }
    if ($s -match '[",]') { return '"' + ($s -replace '"', '""') + '"' }
    return $s
}

$rows = foreach ($b in $all) {
    $forge = [string]$b.forge
    [pscustomobject]@{
        mcversion     = [string]$b.mcversion
        filename      = [string]$b.filename
        type          = [string]$b.type
        patch         = [string]$b.patch
        forge         = $forge
        forge_ok      = (Get-ForgeOk $forge)
        forge_version = (Get-ForgeVersion $forge)
    }
}

# Stable order: newest Minecraft first, then file name, so diffs stay readable.
function ConvertTo-VersionKey([string]$v) {
    $parts = @()
    foreach ($seg in ($v -split '\.')) {
        $m = [regex]::Match($seg, '^\d+')
        $parts += $(if ($m.Success) { [int]$m.Value } else { -1 })
    }
    while ($parts.Count -lt 4) { $parts += -1 }
    return ($parts[0] * 1000000000 + $parts[1] * 1000000 + $parts[2] * 1000 + $parts[3])
}

$sorted = $rows | Sort-Object @{ Expression = { ConvertTo-VersionKey $_.mcversion }; Descending = $true },
                               @{ Expression = { $_.filename }; Descending = $false }

$out = New-Object System.Collections.Generic.List[string]
$out.Add('mcversion,filename,type,patch,forge,forge_ok,forge_version')
foreach ($r in $sorted) {
    $out.Add(($r.mcversion, (Esc $r.filename), $r.type, $r.patch, (Esc $r.forge), $r.forge_ok, $r.forge_version) -join ',')
}

$resolved = [System.IO.Path]::GetFullPath($OutFile)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolved) | Out-Null
[System.IO.File]::WriteAllLines($resolved, $out, (New-Object System.Text.UTF8Encoding($false)))

$na = @($sorted | Where-Object { $_.forge_ok -eq 'false' })
$ok = @($sorted | Where-Object { $_.forge_ok -eq 'true' })
$unknown = @($sorted | Where-Object { $_.forge_ok -eq '' })
Write-Host ("wrote {0}: {1} rows ({2} Forge-compatible, {3} Forge N/A, {4} unknown)" -f `
    $resolved, $sorted.Count, $ok.Count, $na.Count, $unknown.Count)
