# Builds the "SRG member name -> official member name" table used to bridge OptiFine payloads
# onto an official-name Forge runtime.
#
# Inputs (both produced by Forge's own toolchain, no downloads needed):
#   -joined.tsrg        : mcp_config-<mc>.zip's config/joined.tsrg, header "tsrg2 obf srg id"
#   -obfOfficialTsrg    : the obf -> official tsrg (class lines "obf official", member lines
#                         "\tobfName <desc> officialName")
# Output:
#   srgToOfficial.tsv   : "m_127521_<TAB>forThrowable" per line
#
# Why the join is needed: SRG names are global ids (a member's id is unique across the game), but
# the id alone does not give the official name. joined.tsrg ties obf to srg, the obf->official tsrg
# ties obf to official, so obf is the join key. Descriptors in the obf->official file still use
# obfuscated class names, so they are rewritten through the class map before matching.
#
# Usage: pwsh -File tools/build-srg-map.ps1 -JoinedTsrg <path> -ObfOfficialTsrg <path> [-OutFile <path>]

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JoinedTsrg,
    [Parameter(Mandatory = $true)][string]$ObfOfficialTsrg,
    [string]$OutFile = (Join-Path $PSScriptRoot '..\data\mappings\1.21-srg-to-official.tsv')
)

$ErrorActionPreference = 'Stop'

# ---- 1. obf -> official class names, and the member rows ----
$classMap = New-Object 'System.Collections.Generic.Dictionary[string,string]'
$officialRows = New-Object 'System.Collections.Generic.List[object]'
$currentObf = $null

foreach ($line in [System.IO.File]::ReadLines((Resolve-Path $ObfOfficialTsrg))) {
    if ($line.Length -eq 0) { continue }
    if ($line.StartsWith('tsrg2')) { continue }
    if (-not $line.StartsWith("`t")) {
        $parts = $line.Split(' ')
        if ($parts.Length -lt 2) { continue }
        $currentObf = $parts[0]
        # Windows PowerShell 5.1 runs on .NET Framework, where Dictionary has no TryAdd.
        if (-not $classMap.ContainsKey($currentObf)) { $classMap[$currentObf] = $parts[1] }
        continue
    }
    if (-not $currentObf) { continue }
    $parts = $line.TrimStart("`t").Split(' ')
    if ($parts.Length -eq 2) {
        # field without descriptor: name officialName
        $officialRows.Add([pscustomobject]@{ Class = $currentObf; Name = $parts[0]; Desc = ''; Official = $parts[1] })
    } elseif ($parts.Count -ge 3) {
        $officialRows.Add([pscustomobject]@{ Class = $currentObf; Name = $parts[0]; Desc = $parts[1]; Official = $parts[2] })
    }
}
Write-Host ("obf->official: {0} classes, {1} members" -f $classMap.Count, $officialRows.Count)

# official -> obf, for rewriting descriptors
$reverse = New-Object 'System.Collections.Generic.Dictionary[string,string]'
foreach ($pair in $classMap.GetEnumerator()) {
    if (-not $reverse.ContainsKey($pair.Value)) { $reverse[$pair.Value] = $pair.Key }
}

function Convert-DescToObf([string]$desc) {
    if ([string]::IsNullOrEmpty($desc)) { return $desc }
    return [regex]::Replace($desc, 'L([^;]+);', {
        param($m)
        $name = $m.Groups[1].Value
        if ($reverse.ContainsKey($name)) { return 'L' + $reverse[$name] + ';' }
        return $m.Value
    })
}

# ---- 2. obf -> srg from joined.tsrg ----
# fields: "\tname srg id"; methods: "\tname (desc) srg id"; class line: "obf srg id"
$srgByKey = New-Object 'System.Collections.Generic.Dictionary[string,string]'   # class|name|desc -> srg
$srgFieldByKey = New-Object 'System.Collections.Generic.Dictionary[string,string]' # class|name -> srg
$currentClass = $null
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path $JoinedTsrg))) {
    if ($line.Length -eq 0 -or $line.StartsWith('tsrg2')) { continue }
    if (-not $line.StartsWith("`t")) {
        $parts = $line.Split(' ')
        if ($parts.Length -ge 2) { $currentClass = $parts[0] }
        continue
    }
    if (-not $currentClass) { continue }
    $parts = $line.TrimStart("`t").Split(' ')
    if ($parts.Length -eq 3) {
        $key = "$currentClass|$($parts[0])"
        if (-not $srgFieldByKey.ContainsKey($key)) { $srgFieldByKey[$key] = $parts[1] }
    } elseif ($parts.Length -ge 4) {
        $key = "$currentClass|$($parts[0])|$($parts[1])"
        if (-not $srgByKey.ContainsKey($key)) { $srgByKey[$key] = $parts[2] }
    }
}
Write-Host ("obf->srg: {0} methods, {1} fields" -f $srgByKey.Count, $srgFieldByKey.Count)

# ---- 3. join, srg -> official ----
$out = New-Object 'System.Collections.Generic.Dictionary[string,string]'
$matched = 0
$unmatched = 0
foreach ($row in $officialRows) {
    $descObf = Convert-DescToObf $row.Desc
    $srg = $null
    if ($descObf) {
        $key = "$($row.Class)|$($row.Name)|$descObf"
        if ($srgByKey.ContainsKey($key)) { $srg = $srgByKey[$key] }
    }
    if (-not $srg) {
        $key = "$($row.Class)|$($row.Name)"
        if ($srgFieldByKey.ContainsKey($key)) { $srg = $srgFieldByKey[$key] }
    }
    if ($srg) {
        if (-not $out.ContainsKey($srg)) { $out[$srg] = $row.Official; $matched++ }
    } else {
        $unmatched++
    }
}
Write-Host ("joined: {0} srg->official rows, {1} unmatched member rows" -f $matched, $unmatched)

$resolved = [System.IO.Path]::GetFullPath($OutFile)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolved) | Out-Null
$lines = New-Object System.Collections.Generic.List[string]
foreach ($pair in ($out.GetEnumerator() | Sort-Object Key)) { $lines.Add("$($pair.Key)`t$($pair.Value)") }
[System.IO.File]::WriteAllLines($resolved, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("wrote {0} ({1} rows)" -f $resolved, $lines.Count)
