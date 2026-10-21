# Prepares and tests one pre-1.20 line through OptiForge: Forge client install, vanilla jars, Forge dedicated
# server (a world is generated on first start), then a client that joins it with --server/--port.
#
# Lines 1.17-1.19.4 still accept --server/--port (removed in 1.20), which is why these lines can be tested
# end-to-end while 1.20.1 could not: Forge 47 ignores quickPlay and the multiplayer flags arrive too late.
#
# Usage: pwsh -File tools/legacy-rig-test.ps1 -Minecraft 1.19.4 -Forge 45.1.0 -Optifine HD_U_I4 -Edition HD_U
#        [-Java <jdk>] [-Port 25570] [-Seconds 160]
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Minecraft,
    [Parameter(Mandatory = $true)][string]$Forge,
    [Parameter(Mandatory = $true)][string]$Optifine,
    [string]$Edition = 'HD_U',
    [string]$Java,
    [int]$Port = 25570,
    [int]$Seconds = 160
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$rig = 'I:\mods\optifineoforge-test'
$tag = "$Minecraft-$Forge"
$downloads = Join-Path $rig "downloads\forge-install-$tag"
$scratch = Join-Path $downloads 'mc'
$serverDir = Join-Path $downloads 'server'
$installer = Join-Path $downloads "forge-$tag-installer.jar"
$jar = Join-Path $root "build\optifine-pristine\OptiFine_${Minecraft}_$Edition`_$Optifine.jar"
$gameDir = Join-Path $root "build\rig-instance-$Minecraft"
$version = "$Minecraft-forge-$Forge"

if (-not $Java) { $Java = 'C:\Program Files\Java\jdk-17\bin\java.exe' }
if (-not (Test-Path $Java)) { throw "java not found: $Java" }
$javaExe = $Java

New-Item -ItemType Directory -Force -Path $downloads, $scratch, $serverDir, "$gameDir\mods", "$gameDir\optiforge" | Out-Null

function Test-Zip([string]$path) {
    try { $z = [System.IO.Compression.ZipFile]::OpenRead($path); $z.Dispose(); return $true } catch { return $false }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---- OptiFine jar (bmclapi mirror: /<mc>/<edition>/<version>) ----
if (-not (Test-Path $jar) -or -not (Test-Zip $jar)) {
    $url = "https://bmclapi2.bangbang93.com/optifine/$Minecraft/$Edition/$Optifine"
    Write-Host "download $url"
    # The mirror occasionally answers with a 9-byte stub; retry before giving up (measured on 1.17 pre26).
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & curl.exe -sL --max-time 300 -o $jar $url
        if (Test-Zip $jar) { break }
        Write-Host "  attempt $attempt failed, retrying"
        Remove-Item $jar -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
    }
    if (-not (Test-Zip $jar)) {
        # The mirror answers some builds with a 163-byte stub; optifine.net still serves them through
        # adloadx -> downloadx with a one-time token.
        $names = @("OptiFine_${Minecraft}_$Edition`_$Optifine.jar", "preview_OptiFine_${Minecraft}_$Edition`_$Optifine.jar")
        foreach ($name in $names) {
            $adUrl = "https://optifine.net/adloadx?f=$name"
            $html = & curl.exe -sL --max-time 90 $adUrl
            $token = ($html | Select-String -Pattern 'downloadx\?f=[^"'']+' -AllMatches).Matches | Select-Object -First 1
            if (-not $token) { continue }
            $target = "https://optifine.net/$($token.Value)"
            Write-Host "  official fallback: $target"
            & curl.exe -sL --max-time 300 -e $adUrl -o $jar $target
            if (Test-Zip $jar) { break }
        }
    }
    if (-not (Test-Zip $jar)) {
        throw "OptiFine download failed for $Minecraft $Edition $Optifine"
    }
}
Write-Host "OptiFine: $((Get-Item $jar).Length) bytes"

# ---- Forge client install into a self-contained scratch profile ----
if (-not (Test-Path $installer) -or -not (Test-Zip $installer)) {
    # A truncated installer fails later as "Invalid or corrupt jarfile", so validate before using it.
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & curl.exe -sL --max-time 300 -o $installer "https://maven.minecraftforge.net/net/minecraftforge/forge/$tag/forge-$tag-installer.jar"
        if (Test-Zip $installer) { break }
        Write-Host "  installer attempt $attempt failed, retrying"
        Remove-Item $installer -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
    }
    if (-not (Test-Zip $installer)) { throw "Forge installer download failed for $tag" }
}

$manifest = (& curl.exe -sL --max-time 120 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json') | ConvertFrom-Json
$entry = $manifest.versions | Where-Object { $_.id -eq $Minecraft } | Select-Object -First 1
if (-not $entry) { throw "Minecraft $Minecraft is not in Mojang's manifest" }
New-Item -ItemType Directory -Force -Path "$scratch\versions\$Minecraft" | Out-Null
if (-not (Test-Path "$scratch\versions\$Minecraft\$Minecraft.jar")) {
    $vj = (& curl.exe -sL --max-time 120 $entry.url) | ConvertFrom-Json
    & curl.exe -sL --max-time 600 -o "$scratch\versions\$Minecraft\$Minecraft.jar" $vj.downloads.client.url
    & curl.exe -sL --max-time 120 -o "$scratch\versions\$Minecraft\$Minecraft.json" $entry.url
}
if (-not (Test-Path "$scratch\launcher_profiles.json")) {
    '{"profiles":{},"settings":{},"version":3}' | Set-Content -Encoding ASCII "$scratch\launcher_profiles.json"
}
Write-Host "installing Forge client ..."
& $javaExe -jar $installer --installClient $scratch 2>&1 | Select-Object -Last 1

# ---- merge the scratch profile into the shared rig ----
$sourceProfile = Join-Path $scratch "versions\$version\$version.json"
if (-not (Test-Path $sourceProfile)) { throw "installer did not write $sourceProfile" }
New-Item -ItemType Directory -Force -Path "$rig\versions\$version" | Out-Null
Copy-Item $sourceProfile "$rig\versions\$version\" -Force
New-Item -ItemType Directory -Force -Path "$rig\versions\$Minecraft" | Out-Null
Copy-Item "$scratch\versions\$Minecraft\$Minecraft.json" "$rig\versions\$Minecraft\" -Force
if (Test-Path "$scratch\versions\$Minecraft\$Minecraft.jar") {
    Copy-Item "$scratch\versions\$Minecraft\$Minecraft.jar" "$rig\versions\$Minecraft\" -Force
}
$copied = 0
Get-ChildItem "$scratch\libraries" -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring((Join-Path $scratch 'libraries').Length + 1)
    $to = Join-Path "$rig\libraries" $rel
    if (-not (Test-Path $to)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
        Copy-Item $_.FullName $to -Force
        $script:copied++
    }
}
# vanilla libraries the scratch install does not carry (it only downloads Forge's own)
$vanilla = Get-Content "$rig\versions\$Minecraft\$Minecraft.json" -Raw | ConvertFrom-Json
$downloaded = 0
foreach ($lib in $vanilla.libraries) {
    $artifacts = @()
    if ($lib.downloads.artifact) { $artifacts += $lib.downloads.artifact }
    # Natives arrive as classifiers, not as the main artifact: without them java.library.path stays empty and
    # LWJGL dies with "Failed to load a library" (measured on 1.18.2, whose natives jars were never fetched).
    if ($lib.downloads.classifiers) {
        foreach ($name in $lib.downloads.classifiers.PSObject.Properties.Name) {
            if ($name -like '*natives-windows*' -and $name -notlike '*arm64*' -and $name -notlike '*x86*') {
                $artifacts += $lib.downloads.classifiers.$name
            }
        }
    }
    foreach ($artifact in $artifacts) {
        if (-not $artifact -or -not $artifact.path -or -not $artifact.url) { continue }
        $to = Join-Path "$rig\libraries" ($artifact.path -replace '/', '\')
        if (Test-Path $to) { continue }
        New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
        & curl.exe -sL --max-time 300 -o $to $artifact.url
        $script:downloaded++
    }
}
Write-Host "rig libraries: $copied from the installer, $downloaded vanilla"

# ---- dedicated server: first start generates the world ----
$serverInstalled = Test-Path (Join-Path $serverDir "libraries\net\minecraftforge\forge\$tag\win_args.txt")
if (-not $serverInstalled) {
    Write-Host "installing Forge server ..."
    & $javaExe -jar $installer --installServer $serverDir 2>&1 | Select-Object -Last 1
}
'eula=true' | Set-Content -Encoding ASCII (Join-Path $serverDir 'eula.txt')
$level = "RigWorld$($Minecraft -replace '\.','')"
@"
level-name=$level
online-mode=false
server-port=$Port
view-distance=6
max-players=2
"@ | Set-Content -Encoding ASCII (Join-Path $serverDir 'server.properties')

$serverJob = Start-Job -ScriptBlock {
    param($dir, $java, $argsFile)
    Set-Location $dir
    & $java '@user_jvm_args.txt' $argsFile nogui *> (Join-Path $dir 'server-run.log')
} -ArgumentList $serverDir, $javaExe, "@libraries/net/minecraftforge/forge/$tag/win_args.txt"
Write-Host "waiting for the server ..."
$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $text = Get-Content (Join-Path $serverDir 'server-run.log') -Raw -ErrorAction SilentlyContinue
    if ($text -match 'Done \(') { break }
}
$text = Get-Content (Join-Path $serverDir 'server-run.log') -Raw -ErrorAction SilentlyContinue
if ($text -notmatch 'Done \(') { throw "dedicated server did not finish starting: $(Select-String -InputObject $text -Pattern 'ERROR|Exception' | Select-Object -First 1)" }

# ---- client: OptiForge + the user's OptiFine jar, joining the server ----
Get-ChildItem "$gameDir\mods" -Filter 'OptiForge-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem "$gameDir\mods" -Filter '*-classes.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $jar "$gameDir\optiforge\" -Force
$latest = Get-ChildItem "$root\build\libs" -Filter 'OptiForge-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $latest.FullName "$gameDir\mods\" -Force
Remove-Item "$gameDir\crash-reports" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "$gameDir\optiforge\pack" -Recurse -Force -ErrorAction SilentlyContinue

$env:RIG_EXTRA_GAME_ARGS = "--server|127.0.0.1|--port|$Port"
$env:RIG_EXTRA_JVM = "-Doptiforge.libraries=$rig\libraries"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $rig 'launch.ps1') -Rig $rig -VersionId $version -GameDir $gameDir -Mods $latest.FullName -Seconds $Seconds *> $null

$log = Get-Content (Join-Path $gameDir 'logs\latest.log') -ErrorAction SilentlyContinue
$joined = [bool]($log | Select-String -Pattern 'joined the game|logged in with entity id')
$crashes = (Get-ChildItem (Join-Path $gameDir 'crash-reports') -ErrorAction SilentlyContinue | Measure-Object).Count
$optifine = ($log | Select-String -Pattern '\[OptiFine\]' | Measure-Object).Count
$serverLog = Get-Content (Join-Path $serverDir 'server-run.log') -ErrorAction SilentlyContinue
$serverJoined = [bool]($serverLog | Select-String -Pattern 'joined the game')

Write-Output ("RESULT {0} + Forge {1} + OptiFine {2}: joined={3} serverSawJoin={4} crashes={5} optifineLines={6}" -f `
        $Minecraft, $Forge, $Optifine, $joined, $serverJoined, $crashes, $optifine)

Stop-Job $serverJob -ErrorAction SilentlyContinue
Remove-Job $serverJob -Force -ErrorAction SilentlyContinue
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $javaExe } | Stop-Process -Force -ErrorAction SilentlyContinue
