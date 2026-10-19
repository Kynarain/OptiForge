# Installs one Forge client profile into a rig (versions/ + libraries/) so launch.ps1 can run it.
#
# The installer is run with --installClient into a scratch directory, then the profile and every
# library it adds are copied into the rig. Two things the installer does NOT do, both learned the hard
# way: it does not download the vanilla version json (launch.ps1 needs it for inheritsFrom), and it does
# not touch an existing rig, so the merge is this script's job.
#
# Usage:
#   pwsh -File tools/rig-forge-install.ps1 -Rig I:\mods\optifineoforge-test -Minecraft 1.21.6 -Forge 56.0.9

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Rig,
    [Parameter(Mandatory = $true)][string]$Minecraft,
    [Parameter(Mandatory = $true)][string]$Forge,
    [string]$Scratch = '',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$installer = "forge-$Minecraft-$Forge-installer.jar"
if (-not $Scratch) { $Scratch = Join-Path $Rig "downloads\forge-install-$Minecraft-$Forge" }
$mc = Join-Path $Scratch 'mc'
New-Item -ItemType Directory -Force -Path $mc | Out-Null

$jar = Join-Path $Scratch $installer
if (-not (Test-Path $jar)) {
    $url = "https://maven.minecraftforge.net/net/minecraftforge/forge/$Minecraft-$Forge/$installer"
    Write-Host "download $url"
    curl.exe -sL --max-time 300 -o $jar $url
}
"installer: $((Get-Item $jar).Length) bytes"

$profiles = Join-Path $mc 'launcher_profiles.json'
if (-not (Test-Path $profiles)) { '{"profiles":{},"settings":{},"version":3}' | Set-Content -Encoding ASCII $profiles }

Write-Host 'installing Forge client ...'
& (Join-Path $JavaHome 'bin\java.exe') -jar $jar --installClient $mc 2>&1 | Select-Object -Last 2

$versionId = "$Minecraft-forge-$Forge"
$versionDir = Join-Path $mc "versions\$versionId"
if (-not (Test-Path $versionDir)) { throw "the installer did not create $versionDir" }
Copy-Item $versionDir (Join-Path $Rig 'versions') -Recurse -Force
Write-Host "profile copied: versions\$versionId"

# vanilla version json that launch.ps1 resolves inheritsFrom against
$vanillaJson = Join-Path $Rig "versions\$Minecraft\$Minecraft.json"
if ((Test-Path $vanillaJson) -and -not (Test-Path (Join-Path $mc "versions\$Minecraft\$Minecraft.json"))) {
    Copy-Item $vanillaJson (Join-Path $mc "versions\$Minecraft\$Minecraft.json") -Force
}

$profileJson = Get-Content (Join-Path $versionDir "$versionId.json") -Raw | ConvertFrom-Json
$copied = 0
foreach ($library in $profileJson.libraries) {
    $path = $library.downloads.artifact.path
    if (-not $path) { continue }
    $from = Join-Path (Join-Path $mc 'libraries') ($path -replace '/', '\')
    $to = Join-Path (Join-Path $Rig 'libraries') ($path -replace '/', '\')
    if ((Test-Path $from) -and -not (Test-Path $to)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
        Copy-Item $from $to -Force
        $copied++
    }
}
Write-Host "libraries copied: $copied"
Write-Host "done: $versionId"
