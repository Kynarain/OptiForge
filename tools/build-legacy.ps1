# Builds the 1.8-1.12.2 artifact: build/libs-legacy/OptiForge-<ver>-legacy.jar
#
# Why a second build: Forge <= 1.12.2 runs on launchwrapper and parses every class of every jar in mods/
# with ASM 5.2 (FML's DeobfuscationTransformer, ModAccessTransformer, SideTransformer). Class file 61
# (Java 17) is rejected with "Unsupported class file major version", so this artifact is compiled with
# --release 8 from src/legacy/java only; src/main is never compiled into it and is never modified.
#
# It compiles against the runtime it targets: the Forge 1.12.2 universal jar (IFMLLoadingPlugin,
# annotations), launchwrapper-1.12 (IClassTransformer) and asm-debug-all-5.2 (the ASM FML itself loads;
# the legacy jar does not bundle ASM). All three live in a 1.12.2 Forge client install under
# build/phase3/forge-install-1.12.2/mc, which this script creates on first use.
#
# Usage: pwsh -File tools/build-legacy.ps1 [-Jdk <path>] [-Jre8 <path>] [-ForgeHome <mc root>]
param(
    [string]$Jdk = 'C:\Program Files\Java\jdk-21',
    [string]$Jre8 = 'C:\Program Files\Java\jre1.8.0_503',
    [string]$ForgeHome = '',
    [string]$Version = '',
    [string]$Forge1710Jar = '',
    [string]$Rig170 = 'I:\mods\optifineoforge-test'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$deps = Join-Path $root 'build\deps-legacy'
$classes = Join-Path $root 'build\classes-legacy'
$libs = Join-Path $root 'build\libs-legacy'
$pristine = Join-Path $root 'build\optifine-pristine'
$mc = '1.12.2'
$forge = '14.23.5.2860'
$tag = "$mc-$forge"

if (-not $ForgeHome) { $ForgeHome = Join-Path $root 'build\phase3\forge-install-1.12.2\mc' }

if (-not $Version) {
    $line = Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^mod_version_base=' }
    if ($line) { $Version = ($line -split '=')[1].Trim() }
    if (-not $Version) { throw 'mod_version_base is not set in gradle.properties' }
}

function Test-Zip([string]$path) {
    try { $z = [System.IO.Compression.ZipFile]::OpenRead($path); $z.Dispose(); return $true } catch { return $false }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---- a 1.12.2 Forge client install: source of the compile-time API and of binpatches for the probe ----
function Install-ForgeRig {
    New-Item -ItemType Directory -Force -Path "$ForgeHome\versions\$mc" | Out-Null
    $installer = Join-Path $root "build\phase3\forge-install-$tag\forge-$tag-installer.jar"
    New-Item -ItemType Directory -Force -Path (Split-Path $installer -Parent) | Out-Null
    # The mirror answers within seconds; maven.minecraftforge.net stalls from this host (measured).
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (Test-Path $installer -and (Test-Zip $installer)) { break }
        & curl.exe -sL --max-time 300 -o $installer "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/$tag/forge-$tag-installer.jar"
        if (Test-Zip $installer) { break }
        Remove-Item $installer -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
    }
    if (-not (Test-Zip $installer)) { throw "Forge installer download failed for $tag" }
    # The 1.12.2 installer expects the vanilla version next to launcher_profiles.json.
    if (-not (Test-Path "$ForgeHome\versions\$mc\$mc.json")) {
        $manifest = (& curl.exe -sL --max-time 120 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json') | ConvertFrom-Json
        $entry = $manifest.versions | Where-Object { $_.id -eq $mc } | Select-Object -First 1
        $vj = Join-Path $ForgeHome "versions\$mc\$mc.json"
        & curl.exe -sL --max-time 120 -o $vj $entry.url
    }
    if (-not (Test-Path "$ForgeHome\versions\$mc\$mc.jar")) {
        $cached = Join-Path $pristine "$mc.jar"
        if (-not (Test-Path $cached)) {
            $vj = Get-Content "$ForgeHome\versions\$mc\$mc.json" -Raw | ConvertFrom-Json
            New-Item -ItemType Directory -Force -Path $pristine | Out-Null
            & curl.exe -sL --max-time 600 -o $cached $vj.downloads.client.url
        }
        Copy-Item $cached "$ForgeHome\versions\$mc\" -Force
    }
    '{"profiles":{},"settings":{},"version":3}' | Set-Content -Encoding ASCII "$ForgeHome\launcher_profiles.json"
    $java = Join-Path $Jre8 'bin\java.exe'
    if (-not (Test-Path $java)) { throw "JRE 8 not found: $Jre8 (Forge 1.12.2 installer needs Java 8)" }
    Write-Host "installing Forge $tag into $ForgeHome ..."
    & $java -jar $installer --installClient $ForgeHome 2>&1 | Select-Object -Last 1
    if (-not (Test-Path "$ForgeHome\libraries\net\minecraftforge\forge\$tag\forge-$tag.jar")) {
        throw "Forge install did not produce libraries\net\minecraftforge\forge\$tag\forge-$tag.jar"
    }
}

$forgeJar = Join-Path $ForgeHome "libraries\net\minecraftforge\forge\$tag\forge-$tag.jar"
if (-not (Test-Path $forgeJar)) { Install-ForgeRig }

function Find-Library([string]$pattern) {
    $hits = Get-ChildItem (Join-Path $ForgeHome 'libraries') -Recurse -File -Filter $pattern | Sort-Object LastWriteTime -Descending
    if (-not $hits) { throw "library not found under $ForgeHome\libraries : $pattern" }
    return $hits[0].FullName
}
$forgeJar = Find-Library "forge-$tag.jar"
$launchwrapper = Find-Library 'launchwrapper-*.jar'
$asm = Find-Library 'asm-debug-all-*.jar'

$javac = Join-Path $Jdk 'bin\javac.exe'
$jarTool = Join-Path $Jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "javac not found under $Jdk (pass -Jdk)" }

Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libs | Out-Null

$classpath = "$forgeJar;$launchwrapper;$asm"
$sources = Get-ChildItem (Join-Path $root 'src\legacy\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $sources) { throw 'src\legacy\java is empty' }

Write-Host "javac --release 8 $($sources.Count) file(s) with $Jdk"
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp $classpath -d $classes @sources
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $previousPreference
if ($javacExit -ne 0) { throw "javac failed with exit code $javacExit" }

# The runtime reads its own version from this resource (same contract as the modern build).
New-Item -ItemType Directory -Force -Path (Join-Path $classes 'optiforge') | Out-Null
[System.IO.File]::WriteAllText((Join-Path $classes 'optiforge\version.txt'), $Version, (New-Object System.Text.UTF8Encoding($false)))

$out = Join-Path $libs "OptiForge-$Version-legacy.jar"
$manifest = Join-Path $root 'build\MANIFEST-legacy.MF'
$manifestText = @"
Manifest-Version: 1.0
Main-Class: kynarain.cn.optiforge.legacy.LegacyProbe
Implementation-Title: OptiForge (legacy)
Implementation-Version: $Version
Implementation-Vendor: Kynarain
FMLCorePlugin: kynarain.cn.optiforge.legacy.OptiForgeLegacyCorePlugin
FMLCorePluginContainsFMLMod: false

"@
[System.IO.File]::WriteAllText($manifest, $manifestText, (New-Object System.Text.ASCIIEncoding))
& $jarTool --create --file $out --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host ("built {0} ({1} bytes, Java 8 bytecode)" -f $out, (Get-Item $out).Length)

# ---- second artifact: 1.7.10, where FML lives under cpw.mods.fml.* and the 1.8+ binding cannot load ----
# Measured from the 1.7.10-10.13.0.1157 universal jar: 452 cpw/mods/fml entries and no
# net/minecraftforge/fml package; its IFMLLoadingPlugin has the same five methods.
$forge1710 = $Forge1710Jar
if (-not $forge1710) {
    $found = Get-ChildItem (Join-Path $Rig170 'libraries\net\minecraftforge\forge') -Recurse -File `
        -Filter 'forge-1.7.10-*-universal.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($found) { $forge1710 = $found.FullName }
}
if (-not $forge1710 -or -not (Test-Path $forge1710)) {
    throw "Forge 1.7.10 universal jar not found (pass -Forge1710Jar or check $Rig170)"
}

$classes17 = Join-Path $root 'build\classes-legacy17'
$stage17 = Join-Path $root 'build\classes-legacy17-jar'
Remove-Item -Recurse -Force $classes17, $stage17 -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes17, $stage17 | Out-Null
$sources17 = @(Get-ChildItem (Join-Path $root 'src\legacy17\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if (-not $sources17) { throw 'src\legacy17\java is empty' }

Write-Host "javac --release 8 $($sources17.Count) 1.7.10 file(s) against cpw.mods.fml"
$ErrorActionPreference = 'Continue'
& $javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp "$classes;$forge1710" -d $classes17 @sources17
$javacExit17 = $LASTEXITCODE
$ErrorActionPreference = $previousPreference
if ($javacExit17 -ne 0) { throw "javac failed for the 1.7.10 binding with exit code $javacExit17" }

# Shared classes, minus the 1.8+ interface binding (that one links net.minecraftforge.fml and would only
# be dead weight - or a load error - on 1.7.10).
Get-ChildItem $classes -Recurse -File | Where-Object { $_.Name -notlike 'OptiForgeLegacyCorePlugin*' } |
    ForEach-Object {
        $rel = $_.FullName.Substring($classes.Length + 1)
        $to = Join-Path $stage17 $rel
        New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
        Copy-Item $_.FullName $to -Force
    }
Copy-Item (Join-Path $classes17 '*') $stage17 -Recurse -Force

$out17 = Join-Path $libs "OptiForge-$Version-legacy17.jar"
$manifest17 = Join-Path $root 'build\MANIFEST-legacy17.MF'
$manifestText17 = @"
Manifest-Version: 1.0
Main-Class: kynarain.cn.optiforge.legacy.LegacyProbe
Implementation-Title: OptiForge (legacy 1.7.10)
Implementation-Version: $Version
Implementation-Vendor: Kynarain
FMLCorePlugin: kynarain.cn.optiforge.legacy.OptiForgeLegacy17CorePlugin
FMLCorePluginContainsFMLMod: false

"@
[System.IO.File]::WriteAllText($manifest17, $manifestText17, (New-Object System.Text.ASCIIEncoding))
& $jarTool --create --file $out17 --manifest $manifest17 -C $stage17 .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host ("built {0} ({1} bytes, Java 8 bytecode, FMLCorePlugin 1.7.10)" -f $out17, (Get-Item $out17).Length)
exit 0
