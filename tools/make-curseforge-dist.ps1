# Builds the CurseForge upload bundle for OptiForge Reforged.
#
# Naming follows the OptiFabric Reforged convention: <Project>-<SemVer>+mc<Minecraft version>.jar, one file per
# Minecraft version. OptiForge's four artifacts each cover a range of versions, so the range artifacts are copied
# (hard-linked when possible) once per verified Minecraft version; CurseForge then lists one file per game version,
# which is what users expect from a project named "... Reforged".
#
# Only versions verified in this project are emitted. 1.16.5 and 1.13.2 start and load OptiFine but can crash when
# entering a world, so they are marked Beta; 1.7.10 is Alpha.
param(
    [string]$Version = '1.0.1',
    [string]$Project = 'OptiForge-Reforged',
    [string]$Repo = 'I:\mods\OptiForge',
    [string]$OutDirectory = 'I:\mods\OptiForge\dist\cf'
)

$ErrorActionPreference = 'Stop'

# mc, artifact key, forge, optifine example, release type
$lines = @(
    @{ mc = '1.17.1'; kind = 'main'; forge = '37.0.103';   of = '1.17.1 HD_U_H1';        type = 'Release' },
    @{ mc = '1.18';   kind = 'main'; forge = '38.0.14';    of = '1.18 HD_U_H3';          type = 'Release' },
    @{ mc = '1.18.1'; kind = 'main'; forge = '39.1.0';     of = '1.18.1 HD_U_H6';        type = 'Release' },
    @{ mc = '1.18.2'; kind = 'main'; forge = '40.2.9';     of = '1.18.2 HD_U_H9';        type = 'Release' },
    @{ mc = '1.19';   kind = 'main'; forge = '41.1.0';     of = '1.19 HD_U_H9';          type = 'Release' },
    @{ mc = '1.19.1'; kind = 'main'; forge = '42.0.9';     of = '1.19.1 HD_U_H9';        type = 'Release' },
    @{ mc = '1.19.2'; kind = 'main'; forge = '43.2.14';    of = '1.19.2 HD_U_I2';        type = 'Release' },
    @{ mc = '1.19.3'; kind = 'main'; forge = '44.1.23';    of = '1.19.3 HD_U_I3';        type = 'Release' },
    @{ mc = '1.19.4'; kind = 'main'; forge = '45.1.0';     of = '1.19.4 HD_U_I4';        type = 'Release' },
    @{ mc = '1.20.1'; kind = 'main'; forge = '47.4.20';    of = '1.20.1 HD_U_I6';        type = 'Release' },
    @{ mc = '1.20.6'; kind = 'main'; forge = '50.2.14';    of = '1.20.6 HD_U_J1_pre18';  type = 'Release' },
    @{ mc = '1.21';   kind = 'main'; forge = '51.0.33';    of = '1.21 HD_U_J1_pre9';     type = 'Release' },
    @{ mc = '1.21.1'; kind = 'main'; forge = '52.1.20';    of = '1.21.1 HD_U_J1_pre9';   type = 'Release' },
    @{ mc = '1.21.6'; kind = 'main'; forge = '56.0.9';     of = '1.21.6 HD_U_J6_pre3';   type = 'Release' },
    @{ mc = '1.21.7'; kind = 'main'; forge = '57.0.3';     of = '1.21.7 HD_U_J6_pre7';   type = 'Release' },
    @{ mc = '1.21.8'; kind = 'main'; forge = '58.1.26';    of = '1.21.8 HD_U_J6_pre13';  type = 'Release' },
    @{ mc = '1.21.9'; kind = 'main'; forge = '59.0.5';     of = '1.21.9 HD_U_J7_pre2';   type = 'Release' },
    @{ mc = '1.21.10'; kind = 'main'; forge = '60.1.18';   of = '1.21.10 HD_U_J7_pre9';  type = 'Release' },
    @{ mc = '1.21.11'; kind = 'main'; forge = '61.2.4';    of = '1.21.11 HD_U_J9_pre4';  type = 'Release' },
    @{ mc = '26.1.2'; kind = 'main'; forge = '64.1.5';     of = '26.1.2 HD_U_K1_pre2';   type = 'Release' },
    @{ mc = '26.2';   kind = 'main'; forge = '65.1.5';     of = '26.2 HD_U_K2_pre4';     type = 'Release' },
    @{ mc = '1.14.4'; kind = 'oldml'; forge = '28.2.26';   of = '1.14.4 HD_U_G5';        type = 'Release' },
    @{ mc = '1.15.2'; kind = 'oldml'; forge = '31.2.57';   of = '1.15.2 HD_U_G6';        type = 'Release' },
    @{ mc = '1.16.5'; kind = 'oldml'; forge = '36.2.39';   of = '1.16.5 HD_U_G8';        type = 'Beta' },
    @{ mc = '1.13.2'; kind = 'oldml'; forge = '25.0.219';  of = '1.13.2 HD_U_G5';        type = 'Beta' },
    @{ mc = '1.8.9';  kind = 'legacy'; forge = '11.15.1.2318'; of = '1.8.9 HD_U_M5';      type = 'Release' },
    @{ mc = '1.9.4';  kind = 'legacy'; forge = '12.17.0.2317'; of = '1.9.4 HD_U_I5';      type = 'Release' },
    @{ mc = '1.10.2'; kind = 'legacy'; forge = '12.18.3.2511'; of = '1.10.2 HD_U_I5';     type = 'Release' },
    @{ mc = '1.11.2'; kind = 'legacy'; forge = '13.20.1.2505'; of = '1.11.2 HD_U_G5';     type = 'Release' },
    @{ mc = '1.12.2'; kind = 'legacy'; forge = '14.23.5.2860'; of = '1.12.2 HD_U_G5';     type = 'Release' },
    @{ mc = '1.7.10'; kind = 'legacy17'; forge = '10.13.0.1157'; of = '1.7.10 HD_U_E7';   type = 'Alpha' }
)

$sources = @{
    main     = Join-Path $Repo "build\libs\OptiForge-$Version.jar"
    oldml    = Join-Path $Repo "build\libs-oldml\OptiForge-$Version-oldml.jar"
    legacy   = Join-Path $Repo "build\libs-legacy\OptiForge-$Version-legacy.jar"
    legacy17 = Join-Path $Repo "build\libs-legacy\OptiForge-$Version-legacy17.jar"
}
$changelogs = @{
    main     = 'changelog-1.17-26.md'
    oldml    = 'changelog-1.14-1.16.md'
    legacy   = 'changelog-1.8-1.12.md'
    legacy17 = 'changelog-1.7.10.md'
}

foreach ($entry in $sources.Values) {
    if (-not (Test-Path $entry)) { throw "missing build output: $entry" }
}

New-Item -ItemType Directory -Force -Path $OutDirectory | Out-Null
Get-ChildItem $OutDirectory -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force

function Java-For([string]$kind, [string]$mc) {
    if ($kind -ne 'main') { return '8' }
    if ($mc -like '26.*') { return '25' }
    # 1.20.5+ and 1.21.x run on Java 21; 1.17 - 1.20.4 on 17
    $parts = $mc.Split('.')
    $minor = [int]$parts[1]
    if ($minor -eq 20 -and $parts.Count -gt 2 -and [int]$parts[2] -ge 5) { return '21' }
    if ($minor -ge 21) { return '21' }
    return '17'
}

$manifest = @()
foreach ($line in $lines) {
    $source = $sources[$line.kind]
    $target = Join-Path $OutDirectory "$Project-$Version+mc$($line.mc).jar"
    if (Test-Path $target) { Remove-Item $target -Force }
    try {
        New-Item -ItemType HardLink -Path $target -Target $source -ErrorAction Stop | Out-Null
    } catch {
        Copy-Item $source $target -Force
    }
    $hash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
    $manifest += [pscustomobject]@{
        mc = $line.mc
        file = [System.IO.Path]::GetFileName($target)
        bytes = (Get-Item $target).Length
        sha256 = $hash
        release = $line.type
        forge = $line.forge
        optifine = $line.of
        java = (Java-For $line.kind $line.mc)
        artifact = "OptiForge-$Version-$($line.kind)" 
        changelog = $changelogs[$line.kind]
    }
}

$manifestPath = Join-Path $OutDirectory 'upload-manifest.csv'
$manifest | Export-Csv -Path $manifestPath -NoTypeInformation -Encoding UTF8

$md = @('# CurseForge upload list — OptiForge Reforged ' + $Version, '',
        'One file per Minecraft version, named `<Project>-<SemVer>+mc<Minecraft version>.jar` ' +
        '(same convention as OptiFabric Reforged). Upload each row as its own CurseForge file and tick only that ' +
        'Minecraft version.', '',
        '| # | File | Release type | Minecraft | Forge | Example OptiFine build | Java | Changelog |',
        '|---|---|---|---|---|---|---|---|')
$i = 0
foreach ($row in $manifest) {
    $i++
    $md += "| $i | ``$($row.file)`` | $($row.release) | $($row.mc) | $($row.forge) | $($row.optifine) | $($row.java) | ``$($row.changelog)`` |"
}
$md += ''
$md += "Total files: $($manifest.Count). SHA-256 for every file is in ``upload-manifest.csv``."
$mdPath = Join-Path $OutDirectory 'upload-list.md'
$md -join "`r`n" | Set-Content -Encoding UTF8 $mdPath

Write-Host "built $($manifest.Count) upload files in $OutDirectory"
$manifest | Group-Object release | ForEach-Object { Write-Host ("  {0}: {1}" -f $_.Name, $_.Count) }
Write-Host "manifest: $manifestPath"
Write-Host "list    : $mdPath"
