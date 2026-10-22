# Installs the four manual-test lines (1.13.2, 1.16.5, 1.7.10, 1.20.1) into the official launcher folder:
# version profiles + their libraries are copied from the rig, each line gets its own game directory under
# <home>\OptiForge-test\<mc>\ with the right OptiForge artifact in mods/ and the OptiFine jar in optiforge/,
# and one launcher profile per line is added to launcher_profiles.json (existing profiles are preserved).
#
# Why separate game directories: the artifacts are exclusive (1.13.2-1.16.5 needs -oldml, <=1.12.2 needs
# -legacy, 1.7.10 needs -legacy17, 1.17+ needs the main jar), so a single shared mods/ folder cannot hold them.
param(
    [string]$MinecraftHome = 'C:\Users\kynar\AppData\Roaming\.minecraft',
    [string]$Rig = 'I:\mods\optifineoforge-test',
    [string]$Repo = 'I:\mods\OptiForge',
    [string]$Version = '0.16.3'
)

$ErrorActionPreference = 'Stop'

$lines = @(
    @{ mc = '1.13.2'; profile = '1.13.2-forge-25.0.219';        source = '1.13.2-forge-25.0.219';        of = 'OptiFine_1.13.2_HD_U_G5.jar';  mod = "OptiForge-$Version-oldml.jar";   java = 8 },
    @{ mc = '1.16.5'; profile = '1.16.5-forge-36.2.39';         source = '1.16.5-forge-36.2.39-j8';     of = 'OptiFine_1.16.5_HD_U_G8.jar';  mod = "OptiForge-$Version-oldml.jar";   java = 8 },
    @{ mc = '1.7.10'; profile = '1.7.10-forge-1.7.10-10.13.0.1157'; source = '1.7.10-forge-1.7.10-10.13.0.1157';   of = 'OptiFine_1.7.10_HD_U_E7.jar';  mod = "OptiForge-$Version-legacy17.jar"; java = 8 },
    @{ mc = '1.20.1'; profile = '1.20.1-forge-47.4.20';         source = '1.20.1-forge-47.4.20';        of = 'OptiFine_1.20.1_HD_U_I6.jar';  mod = "OptiForge-$Version.jar";         java = 17 }
)

function Copy-Missing([string]$from, [string]$to) {
    if (-not (Test-Path $from)) { return 0 }
    try {
        $item = Get-Item $from
        if (-not (Test-Path $to)) {
            New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
            Copy-Item $from $to -Force
            return 1
        }
    } catch {
        return 0
    }
    return 0
}

function Copy-LibrariesFor([string]$profileJsonPath, [string]$profileJsonText) {
    $copied = 0
    $json = $profileJsonText | ConvertFrom-Json
    $sources = @($json)
    if ($json.inheritsFrom) {
        $parent = Join-Path $Rig "versions\$($json.inheritsFrom)\$($json.inheritsFrom).json"
        if (Test-Path $parent) { $sources += (Get-Content $parent -Raw | ConvertFrom-Json) }
    }
    foreach ($source in $sources) {
        if (-not $source.libraries) { continue }
        foreach ($lib in $source.libraries) {
            $artifacts = @()
            if ($lib.downloads.artifact -and $lib.downloads.artifact.path) { $artifacts += $lib.downloads.artifact.path }
            if ($lib.downloads.classifiers) {
                foreach ($name in $lib.downloads.classifiers.PSObject.Properties.Name) {
                    if ($name -like '*natives-windows*' -and $name -notlike '*arm64*' -and $name -notlike '*x86*') {
                        $artifacts += $lib.downloads.classifiers.$name.path
                    }
                }
            }
            foreach ($path in $artifacts) {
                if (-not $path) { continue }
                $rel = $path -replace '/', '\'
                $copied += Copy-Missing (Join-Path $Rig "libraries\$rel") (Join-Path $MinecraftHome "libraries\$rel")
            }
        }
    }
    return $copied
}

$totalLibs = 0
$summary = @()
New-Item -ItemType Directory -Force -Path (Join-Path $MinecraftHome 'versions') | Out-Null
$gameRoot = Join-Path $MinecraftHome 'OptiForge-test'
New-Item -ItemType Directory -Force -Path $gameRoot | Out-Null

foreach ($line in $lines) {
    $srcDir = Join-Path $Rig "versions\$($line.source)"
    $srcJson = Join-Path $srcDir "$($line.source).json"
    if (-not (Test-Path $srcJson)) { throw "missing rig profile $srcJson" }
    $text = Get-Content $srcJson -Raw
    $json = $text | ConvertFrom-Json

    # Official launcher needs the id inside the json to match the folder name.
    $json.id = $line.profile
    # Ask the launcher for the runtime this line actually needs instead of letting it default to the newest
    # JVM it has: 1.13-1.7 are Java 8 only (jre-legacy), 1.20.1 is Java 17 (java-runtime-beta) and Forge 47
    # is not supported on 21.
    $component = if ($line.java -eq 8) { 'jre-legacy' } else { 'java-runtime-beta' }
    $json | Add-Member -NotePropertyName javaVersion -NotePropertyValue ([pscustomobject]@{ component = $component; majorVersion = $line.java }) -Force
    $destDir = Join-Path $MinecraftHome "versions\$($line.profile)"
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    ($json | ConvertTo-Json -Depth 20) | Set-Content -Encoding UTF8 (Join-Path $destDir "$($line.profile).json")

    # Client jar: the profile folder needs <id>.jar; fall back to the vanilla jar copy in the rig.
    $srcJar = Join-Path $srcDir "$($line.source).jar"
    if (-not (Test-Path $srcJar)) { $srcJar = Join-Path $Rig "versions\$($line.mc)\$($line.mc).jar" }
    $destJar = Join-Path $destDir "$($line.profile).jar"
    if (Test-Path $srcJar) { Copy-Item $srcJar $destJar -Force } else { throw "missing client jar for $($line.mc)" }

    $totalLibs += Copy-LibrariesFor $srcJson $text

    # Per-line game directory: mods/ + optiforge/ only, no shared state.
    $gameDir = Join-Path $gameRoot $line.mc
    New-Item -ItemType Directory -Force -Path (Join-Path $gameDir 'mods'), (Join-Path $gameDir 'optiforge') | Out-Null
    Get-ChildItem (Join-Path $gameDir 'mods') -File -Filter 'OptiForge-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    $modSource = Join-Path $Repo "build\libs-legacy\$($line.mod)"
    if (-not (Test-Path $modSource)) { $modSource = Join-Path $Repo "build\libs-oldml\$($line.mod)" }
    if (-not (Test-Path $modSource)) { $modSource = Join-Path $Repo "build\libs\$($line.mod)" }
    if (-not (Test-Path $modSource)) { throw "missing artifact $($line.mod)" }
    Copy-Item $modSource (Join-Path $gameDir 'mods') -Force
    $ofSource = Join-Path $Repo "build\optifine-pristine\$($line.of)"
    if (-not (Test-Path $ofSource)) { throw "missing OptiFine $($line.of)" }
    Copy-Item $ofSource (Join-Path $gameDir 'optiforge') -Force
    Remove-Item (Join-Path $gameDir 'optiforge\pack') -Recurse -Force -ErrorAction SilentlyContinue

    $summary += [pscustomobject]@{
        MC = $line.mc; Profile = $line.profile; Java = $line.java
        Mod = $line.mod; OptiFine = $line.of; GameDir = $gameDir
    }
}

# --- launcher_profiles.json ----------------------------------------------------------------------------
$profilesPath = Join-Path $MinecraftHome 'launcher_profiles.json'
$backup = "$profilesPath.optiforge-backup"
if (-not (Test-Path $backup)) { Copy-Item $profilesPath $backup -Force }
$profiles = Get-Content $profilesPath -Raw | ConvertFrom-Json
if (-not $profiles.profiles) { $profiles | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) -Force }
foreach ($line in $lines) {
    $id = "optiforge-test-$($line.mc)"
    $entry = [pscustomobject]@{
        created = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        gameDir = (Join-Path $gameRoot $line.mc)
        icon = 'Grass'
        javaDir = ''
        javaArgs = ''
        lastUsed = '1970-01-01T00:00:00.000Z'
        lastVersionId = $line.profile
        name = "OptiForge 测试 $($line.mc)"
        type = 'custom'
    }
    $profiles.profiles | Add-Member -NotePropertyName $id -NotePropertyValue $entry -Force
}
($profiles | ConvertTo-Json -Depth 20) | Set-Content -Encoding UTF8 $profilesPath

Write-Host ''
Write-Host '=== 已装入官方启动器 ==='
$summary | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "libraries copied: $totalLibs"
Write-Host "launcher profiles: optiforge-test-1.13.2 / -1.16.5 / -1.7.10 / -1.20.1"
Write-Host "launcher_profiles.json backup: $backup"
