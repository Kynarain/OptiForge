# Real shader test through OptiForge: download high-download shader packs from Modrinth, enable one at a
# time, launch the 1.21.7 rig instance with quickPlay, then read the shader evidence out of latest.log.
#
# What counts as evidence (and why):
#  - OptiFine logs every shader-pack/program event through net.optifine.shaders.SMCLog, which prefixes
#    "[Shaders] " (SMCLog.PREFIX). So "[Shaders] Loaded shaderpack: <file>" proves the pack was enabled,
#    and the "Program loaded: <name>" / "Compute program loaded: <name>" lines are one per GL program
#    OptiFine compiled and linked for rendering. A pack that fails to compile still prints its errors in
#    this same channel: "Error compiling vertex shader:", "Error linking program:",
#    "[Shaders] Error: Invalid program ..." - which is exactly why the compile lines are the evidence and
#    "the game started" is not.
#  - The selected pack is NOT stored in optionsof.txt. Shaders.configFile is <game>/optionsshaders.txt
#    (Shaders.<clinit>: new File(Minecraft.gameDirectory, "optionsshaders.txt")) and the key is the
#    EnumShaderOption.SHADER_PACK property key "shaderPack", a java.util.Properties entry, so the line is
#    shaderPack=<exact file name>. The game rewrites it on exit; this script writes it before every run.
#    Measured: writing the same line into optionsof.txt instead only produces
#    "[OptiFine] Skipping bad option: shaderPack=..." (optionsof.txt has a different parser) and the pack
#    still does not load.
#
#  - OptiFine's own 1.21.6 and 1.21.7 previews (HD_U_J6_pre3 / HD_U_J6_pre7) cannot load a shader pack at
#    all: Shaders.loadShaderPack() contains an unconditional `flag = true` right after it reads the
#    "shaderPack" property (`iconst_1; istore_2; iload_2; ifne` in bytecode), so getShaderPack() is never
#    called and the client logs "[Shaders] No shaderpack loaded." on every launch - on vanilla too, this is
#    not an OptiForge interaction. The same method is normal (`iload_2; ifne`) in the 1.21.8 / 1.21.9 /
#    1.21.10 / 1.21.11 / 26.x previews, so those lines are where a real compile test can run. Verified
#    with javap on build\optifine-pristine\*.jar; docs/SHADERS.md records the per-line result.
#
# Usage:
#   pwsh -File tools/shader-test.ps1 -Pack all -Seconds 150                       # 1.21.7 (default)
#   pwsh -File tools/shader-test.ps1 -Pack all -VersionId 1.21.8-forge-58.1.26 `
#        -GameDir build\rig-instance-1.21.8 -Seconds 150                          # a line that loads packs
#   pwsh -File tools/shader-test.ps1 -Pack BSL_v10.1.8.zip
#   pwsh -File tools/shader-test.ps1 -Pack complementary-reimagined -Seconds 120
#
# Output: per-pack counts + the exact matching log lines, downloads\shaders\logs\<file>.log (full log) and
# downloads\shaders\logs\<file>.shaders.log (just the shader lines), and downloads\shaders\results.json.

[CmdletBinding()]
param(
    [string]$Pack = 'all',
    [int]$Seconds = 150,
    [string]$VersionId = '1.21.7-forge-57.0.3',
    [string]$GameDir = 'build\rig-instance-1.21.7',
    [string]$Rig = 'I:\mods\optifineoforge-test',
    [string]$World = 'CaptureWorld',
    [string]$McVersion = '1.21.7',
    [string]$Jar = '',
    [string]$Downloads = '',
    [int]$WaitMinutes = 10,
    # Only re-run already-downloaded packs; no Modrinth query.
    [switch]$SkipDownload
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not [System.IO.Path]::IsPathRooted($GameDir)) { $GameDir = Join-Path $root $GameDir }
if (-not $Downloads) { $Downloads = Join-Path $Rig 'downloads\shaders' }
if (-not (Test-Path $Downloads)) { New-Item -ItemType Directory -Force -Path $Downloads | Out-Null }
$logsDir = Join-Path $Downloads 'logs'
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

# Default pack set = the three highest-download shader projects on Modrinth that still ship a pack covering
# the client line under test. Edit this list to test others; slugs are Modrinth project slugs.
$defaultSlugs = @('complementary-reimagined', 'bsl-shaders', 'makeup-ultra-fast-shaders')

function Get-Json([string]$url, [int]$attempts = 3) {
    for ($i = 1; $i -le $attempts; $i++) {
        $text = & curl.exe -sSL --max-time 90 $url 2>&1
        if ($text -is [array]) { $text = $text -join "`n" }
        if ($text -match '^\s*[\[{]') {
            try { return ($text | ConvertFrom-Json) } catch { }
        }
        Write-Host "  (retry $i) $url"
        Start-Sleep -Seconds 3
    }
    throw "Modrinth request failed: $url"
}

# Picks the newest file of a project whose game_versions cover $McVersion and whose file is a .zip. Newest
# by date_published, not by array order: the API returns versions newest-first today, but that is not a
# documented contract and a wrong pick would quietly test an old pack.
function Get-NewestPackVersion($versions, [string]$mc) {
    $candidates = @($versions | Where-Object {
            ($_.game_versions -contains $mc) -and (@($_.files | Where-Object { $_.filename -like '*.zip' }).Count -gt 0)
        })
    if (-not $candidates.Count) { return $null }
    $candidates | Sort-Object { [datetime]$_.date_published } -Descending | Select-Object -First 1
}

function Get-PackManifest {
    $manifestPath = Join-Path $Downloads 'packs.json'
    if ($SkipDownload -and (Test-Path $manifestPath)) {
        # PS 5.1's ConvertFrom-Json hands back a JSON array as ONE object, so @() around it would produce a
        # one-element array holding the array; enumerate explicitly instead (measured: it silently turned
        # 3 manifest rows into one nested array and every File property into a 3-element array).
        $parsed = Get-Content $manifestPath -Raw | ConvertFrom-Json
        $known = @()
        foreach ($item in $parsed) { $known += $item }
        return $known
    }
    $entries = @()
    foreach ($slug in $defaultSlugs) {
        Write-Host "querying Modrinth: $slug"
        $project = Get-Json "https://api.modrinth.com/v2/project/$slug"
        $versions = Get-Json "https://api.modrinth.com/v2/project/$slug/version"
        $version = Get-NewestPackVersion $versions $McVersion
        if (-not $version) {
            Write-Host "  WARNING: $slug has no .zip version covering $McVersion - skipped"
            continue
        }
        $file = @($version.files | Where-Object { $_.filename -like '*.zip' })[0]
        $entries += [pscustomobject]@{
            Title     = $project.title
            Slug      = $slug
            Downloads = [int64]$project.downloads
            Version   = $version.version_number
            Published = $version.date_published
            McVersion = $McVersion
            File      = $file.filename
            Bytes     = [int64]$file.size
            Url       = $file.url
        }
    }
    if (-not $entries.Count) { throw 'no shader pack versions covering ' + $McVersion }
    $entries | ConvertTo-Json | Set-Content -Path $manifestPath -Encoding UTF8
    return $entries
}

function Sync-PackZip($entry) {
    $dest = Join-Path $Downloads $entry.File
    $ok = (Test-Path $dest) -and ((Get-Item $dest).Length -eq $entry.Bytes)
    if ($ok) { return $dest }
    for ($i = 1; $i -le 3; $i++) {
        Write-Host "  download $($entry.File) ($($entry.Bytes) bytes)"
        & curl.exe -sSL --max-time 300 -o $dest $entry.Url
        if ((Test-Path $dest) -and (Get-Item $dest).Length -eq $entry.Bytes) { return $dest }
        Write-Host "  (retry $i) size mismatch"
        Remove-Item $dest -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
    }
    throw "shader pack download failed: $($entry.Url)"
}

# One client at a time. A second client shares the natives folder, the GPU and the window title, so a
# "shader test" can end up watching the other instance. Both checks are used: the window title (the
# project's own rule for this machine) and the JVM command line (a client that has not opened its window
# yet has no title, and would otherwise start a second one right behind us).
function Wait-ExclusiveClient {
    $deadline = (Get-Date).AddMinutes($WaitMinutes)
    while ((Get-Date) -lt $deadline) {
        $windows = @(Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like '*Minecraft*' })
        $procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -match 'net\.minecraft|modlauncher\.Launcher|fml\.startup\.Client' })
        if (-not $windows.Count -and -not $procs.Count) { return }
        Write-Host ("  another client is running ({0} window(s), {1} JVM(s)); waiting 30 s" -f $windows.Count, $procs.Count)
        Start-Sleep -Seconds 30
    }
    throw "a Minecraft client is still running after $WaitMinutes minutes; close it and rerun"
}

function Write-OptionsShaders([string]$gameDir, [string]$packFile) {
    # java.util.Properties format; Properties.load accepts '=' and the game writes '=' too.
    $path = Join-Path $gameDir 'optionsshaders.txt'
    [System.IO.File]::WriteAllText($path, "shaderPack=$packFile`r`n", (New-Object System.Text.UTF8Encoding($false)))
    # Note: optionsof.txt is deliberately not touched. OptiFine reads the shader pack from optionsshaders.txt
    # only; an identical line in optionsof.txt is reported as "Skipping bad option" and changes nothing.
}

function Test-ShaderLog([string]$latestLog) {
    $lines = @()
    if (Test-Path $latestLog) { $lines = @(Get-Content $latestLog) }
    $shaderLines = @($lines | Select-String -Pattern '\[Shaders\]|SMCLog' | ForEach-Object { $_.Line })
    $programLines = @($lines | Select-String -Pattern 'Program loaded:|Compute program loaded:' | ForEach-Object { $_.Line })
    $compileLines = @($lines | Select-String -Pattern 'Error compiling|Error linking|Error linking program|\[Shaders\] Error|Invalid program|GLSL' | ForEach-Object { $_.Line })
    # "Failed" is too noisy on its own: vanilla's ShaderManager and post chains fail independently of
    # OptiFine (measured on 1.21.7: "Failed to parse post chain at minecraft:post_effect/fxaa_of_2x.json"
    # appears with zero shader programs loaded). Only shader-pack-scoped failures are counted.
    $failLines = @($lines | Select-String -Pattern '\[Shaders\].*([Ff]ail|[Ee]rror)|[Ss]hader ?[Pp]ack.*[Ff]ail|[Ff]ail(ed)? to (compile|link)' | ForEach-Object { $_.Line })
    $loadedLine = @($lines | Select-String -Pattern 'Loaded shaderpack:' | ForEach-Object { $_.Line })
    $noneLine = @($lines | Select-String -Pattern 'No shaderpack loaded' | ForEach-Object { $_.Line })
    $worldLine = @($lines | Select-String -Pattern 'logged in with entity id|joined the game|Loaded .* world' | ForEach-Object { $_.Line })
    $ofLines = @($lines | Select-String -Pattern '\[OptiFine\]' | ForEach-Object { $_.Line })
    $errorLines = @($lines | Select-String -Pattern '\bERROR\b|Exception|Caused by' | ForEach-Object { $_.Line })
    [pscustomobject]@{
        LogFile        = $latestLog
        LogLines       = $lines.Count
        ShaderLines    = $shaderLines
        ProgramLines   = $programLines
        CompileLines   = $compileLines
        FailLines      = $failLines
        LoadedLine     = $loadedLine
        NoneLine       = $noneLine
        WorldLine      = $worldLine
        OptiFineLines  = $ofLines.Count
        ErrorLines     = $errorLines
    }
}

function Copy-PackLog($packFile, [string]$lineTag) {
    $gameLog = Join-Path $GameDir 'logs\latest.log'
    $base = [System.IO.Path]::GetFileNameWithoutExtension($packFile)
    # The line tag keeps a 1.21.8 run from overwriting the 1.21.7 evidence for the same pack file.
    $logCopy = Join-Path $logsDir "$lineTag-$base.log"
    if (Test-Path $gameLog) { Copy-Item $gameLog $logCopy -Force }
    $analysis = Test-ShaderLog $gameLog
    if ($analysis.ShaderLines.Count) {
        $analysis.ShaderLines | Set-Content -Path (Join-Path $logsDir "$lineTag-$base.shaders.log") -Encoding UTF8
    }
    $analysis | Add-Member -NotePropertyName LogCopy -NotePropertyValue $logCopy
    return $analysis
}

# ---- pack selection ---------------------------------------------------------
$manifest = Get-PackManifest
if ($Pack -eq 'all') {
    $selected = $manifest
} else {
    $selected = @($manifest | Where-Object { $_.File -eq $Pack -or $_.Slug -eq $Pack -or $_.Title -eq $Pack -or $_.File -like $Pack -or $_.Slug -like $Pack })
    if (-not $selected.Count) {
        throw ("unknown pack '$Pack'; known: " + (($manifest | ForEach-Object { "$($_.Slug) ($($_.File))" }) -join ', '))
    }
}

# ---- jar / instance preconditions ------------------------------------------
if (-not $Jar) {
    $propsPath = Join-Path $root 'gradle.properties'
    $version = ((Get-Content $propsPath | Where-Object { $_ -match '^mod_version_base=' }) -split '=', 2)[1].Trim()
    $Jar = Join-Path $root "build\libs\OptiForge-$version.jar"
    if (-not (Test-Path $Jar)) {
        $Jar = (Get-ChildItem (Join-Path $root 'build\libs') -Filter 'OptiForge-*.jar' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    }
}
$Jar = (Resolve-Path $Jar).Path
if (-not (Test-Path $GameDir)) { throw "no rig instance at $GameDir" }
$optifineJars = @(Get-ChildItem (Join-Path $GameDir 'optiforge') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'OptiFine' })
if (-not $optifineJars.Count) { throw "no OptiFine jar in $GameDir\optiforge" }
$shaderDir = Join-Path $GameDir 'shaderpacks'
New-Item -ItemType Directory -Force -Path $shaderDir | Out-Null

Write-Host "jar under test : $(Split-Path -Leaf $Jar)"
Write-Host "instance       : $GameDir"
Write-Host "version id     : $VersionId"
Write-Host "optifine       : $($optifineJars[0].Name)"
Write-Host "packs          : $(($selected | ForEach-Object { $_.File }) -join ', ')"
Write-Host ''

# ---- run each pack ----------------------------------------------------------
$results = @()
foreach ($entry in $selected) {
    $packFile = Sync-PackZip $entry
    Write-Host "===== $($entry.Title) - $($entry.File) ($($entry.Version))"
    Wait-ExclusiveClient

    # Only the pack under test stays in shaderpacks/: an extracted folder of another pack is also a
    # selectable pack, and a stale zip makes the log harder to attribute.
    Get-ChildItem $shaderDir -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item $packFile $shaderDir -Force
    Write-OptionsShaders $GameDir $entry.File
    Remove-Item (Join-Path $GameDir 'crash-reports') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $GameDir 'logs\latest.log') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $GameDir "saves\$World\session.lock") -Force -ErrorAction SilentlyContinue

    $verifyArgs = @{
        VersionId = $VersionId
        GameDir   = $GameDir
        Rig       = $Rig
        World     = $World
        Seconds   = $Seconds
        Jar       = $Jar
    }
    # The pack cache is deliberately NOT kept: its key hashes only the OptiFine/vanilla input jars, so a
    # pack built by an older OptiForge build would keep being served and a regression would hide.
    $launchOut = & (Join-Path $PSScriptRoot 'rig-verify.ps1') @verifyArgs 2>&1
    $launchOut | Select-Object -Last 8 | ForEach-Object { "  $_" }

    $analysis = Copy-PackLog $entry.File $VersionId
    $crashes = @(Get-ChildItem (Join-Path $GameDir 'crash-reports') -Filter '*.txt' -ErrorAction SilentlyContinue)
    $result = [pscustomobject]@{
        Title       = $entry.Title
        Slug        = $entry.Slug
        File        = $entry.File
        Downloads   = $entry.Downloads
        Version     = $entry.Version
        Line        = $VersionId
        Loaded      = [bool]$analysis.LoadedLine.Count
        NoPack      = [bool]$analysis.NoneLine.Count
        Programs    = $analysis.ProgramLines.Count
        ShaderLines = $analysis.ShaderLines.Count
        CompileErrs = $analysis.CompileLines.Count
        FailLines   = $analysis.FailLines.Count
        World       = [bool]$analysis.WorldLine.Count
        Crashes     = $crashes.Count
        LogCopy     = $analysis.LogCopy
    }
    $results += $result

    Write-Host ("  loaded={0} programs={1} [Shaders]={2} errors={3} world={4} crashes={5}" -f `
        $result.Loaded, $result.Programs, $result.ShaderLines, $result.CompileErrs, $result.World, $result.Crashes)
    if ($result.NoPack -and -not $result.Loaded) {
        Write-Host '  note: OptiFine reported "No shaderpack loaded." - the file is in shaderpacks and optionsshaders.txt is set,'
        Write-Host '        so check that this OptiFine build still has shader pack loading (1.21.6/1.21.7 J6 previews do not).'
    }
    if ($analysis.LoadedLine.Count) { Write-Host ("  evidence: " + $analysis.LoadedLine[0]) }
    if ($analysis.ProgramLines.Count) { Write-Host ("  compile : " + $analysis.ProgramLines[0]) }
    $analysis.CompileLines | Select-Object -First 3 | ForEach-Object { "  ERROR   : $_" }
    $analysis.FailLines | Select-Object -First 3 | ForEach-Object { "  FAIL    : $_" }
    if ($analysis.WorldLine.Count) { Write-Host ("  world   : " + $analysis.WorldLine[0]) }
    Write-Host ''
}

# ---- summary ---------------------------------------------------------------
Write-Host '===== SUMMARY ====='
$results | ForEach-Object {
    "{0,-38} loaded={1,-5} programs={2,3} [Shaders]={3,4} errors={4,2} world={5,-5} crashes={6}" -f `
        $_.Title, $_.Loaded, $_.Programs, $_.ShaderLines, $_.CompileErrs, $_.World, $_.Crashes
}
# results.json is per (version line, pack): rerunning one pack replaces only its own row, so evidence for
# another line/instance is not silently dropped.
$resultsPath = Join-Path $Downloads 'results.json'
$merged = @()
if (Test-Path $resultsPath) {
    # Same PS 5.1 note as in Get-PackManifest: ConvertFrom-Json returns the array as a single object, so
    # enumerate it with foreach rather than decorating it with @().
    $parsed = Get-Content $resultsPath -Raw | ConvertFrom-Json
    foreach ($old in $parsed) {
        $superseded = $false
        foreach ($new in $results) { if ($new.Line -eq $old.Line -and $new.File -eq $old.File) { $superseded = $true } }
        if (-not $superseded) { $merged += $old }
    }
}
foreach ($new in $results) { $merged += $new }
$merged | ConvertTo-Json -Depth 4 | Set-Content -Path $resultsPath -Encoding UTF8
Write-Host "results: $resultsPath"
Write-Host "logs   : $logsDir"
