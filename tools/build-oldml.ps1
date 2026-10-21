# Builds build/libs-oldml/OptiForge-<ver>-oldml.jar: the Java-8 artifact for Forge 1.13.2 / 1.14.4 /
# 1.15.2 / 1.16.5.
#
# Why a second artifact: SC 1.13-1.16 run on Java 8, and ModLauncher 2-8 (1.13-1.16) has a different
# ITransformationService shape than the 9/10 used from 1.17 on (`void beginScanning` abstract, no
# targetPreClass, no additionalClassesLocator on 2.1.1). The current OptiForge jar is compiled with
# --release 17 against ModLauncher 10, so it cannot even load there.
#
# This script compiles the shared pipeline (src/main/java, minus the Java-17-only entry point/module
# classes) plus src/oldml/java with --release 8 against ModLauncher 4.1.0 (the 1.14.4 line). Classes
# compiled against 4.1.0 load and link on 5.1.2 (1.15.2), 8.1.3 (1.16.5) and 2.1.1 (1.13.2; its voting
# context is an empty marker interface, which the transformer handles reflectively) - verified by
# tools/check-oldml.ps1 on a Java 8 runtime.
#
# 1.13.2 is a different OptiFine payload generation (flat patch/<obf>.class.xdelta + root
# net/optifine/**, all in the obfuscated namespace). Its obf -> runtime bridge is shipped inside the
# artifact as optiforge/mappings/1.13.2-obf-to-srg.tsrg, extracted here from the 1.13.2 mcp_config
# artifact (config/joined.tsrg) that Forge 25.0.219 installed into the rig.
#
# ASM: the 1.14.4 runtime ships ASM 6.2, 1.16.5 ships ASM 9.1. Compile against 6.2 (the oldest) so no
# ASM-7+-only API can slip in; the artifact does not bundle ASM (the game supplies it).
#
# Usage: pwsh -File tools/build-oldml.ps1 [-Jdk <path>] [-ForgeHome14 <dir>] [-McpConfig13 <zip>] [-Version <v>]
[CmdletBinding()]
param(
    [string]$Jdk = 'C:\Program Files\Java\jdk-11.0.32',
    [string]$ForgeHome14 = 'I:\mods\optifineoforge-test\downloads\1.14.4-28.2.26\mc',
    [string]$McpConfig13 = '',
    [string]$Version = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build\libs-oldml'
$classes = Join-Path $root 'build\classes-oldml'
$resources = Join-Path $root 'src\main\resources'

# The version comes from gradle.properties so this build and the Gradle build can never disagree.
if (-not $Version) {
    $line = Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^mod_version_base=' }
    if ($line) { $Version = ($line -split '=')[1].Trim() }
    if (-not $Version) { throw 'mod_version_base is not set in gradle.properties' }
}

$javac = Join-Path $Jdk 'bin\javac.exe'
$jarTool = Join-Path $Jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "javac not found under $Jdk (pass -Jdk; JDK 9+ is required for --release 8)" }
if (-not (Test-Path $jarTool)) { throw "jar tool not found under $Jdk" }

# ---- dependencies: oldest ModLauncher of the range + its options parser + the ASM of 1.14.4 ----
function Find-Jar([string]$dir, [string]$pattern, [string]$what) {
    $found = Get-ChildItem $dir -Recurse -File -Filter $pattern -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -First 1
    if (-not $found) { throw "could not find $what under $dir (pattern $pattern)" }
    return $found.FullName
}
$modlauncher = Join-Path $ForgeHome14 'libraries\cpw\mods\modlauncher\4.1.0\modlauncher-4.1.0.jar'
if (-not (Test-Path $modlauncher)) { throw "ModLauncher 4.1.0 not found at $modlauncher (install the 1.14.4 Forge profile first)" }
$jopt = Find-Jar (Join-Path $ForgeHome14 'libraries\net\sf\jopt-simple') 'jopt-simple-*.jar' 'jopt-simple (ModLauncher API signature)'
$asm = Find-Jar (Join-Path $ForgeHome14 'libraries\org\ow2\asm\asm') 'asm-6.2.jar' 'ASM 6.2'
$asmTree = Find-Jar (Join-Path $ForgeHome14 'libraries\org\ow2\asm\asm-tree') 'asm-tree-6.2.jar' 'asm-tree 6.2'
$asmCommons = Find-Jar (Join-Path $ForgeHome14 'libraries\org\ow2\asm\asm-commons') 'asm-commons-6.2.jar' 'asm-commons 6.2'

# ---- sources: shared pipeline (all of src/main/java) minus the Java-17-only classes, plus src/oldml ----
$java17Only = @(
    'OptiForgeService.java',        # ML 10 entry: findModuleLayerManager/IModuleLayerManager, targetPreClass
    'ModernTargets.java',           # Target.targetPreClass (ML 7+)
    'ModuleReads.java',             # java.lang.Module / IModuleLayerManager
    'ModuleReadsInjector.java'      # ASM injection that installs module reads
)
$sources = New-Object System.Collections.Generic.List[string]
$skipped = New-Object System.Collections.Generic.List[string]
Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object {
    if ($java17Only -contains $_.Name) { $skipped.Add($_.Name); return }
    $sources.Add($_.FullName)
}
Get-ChildItem (Join-Path $root 'src\oldml\java') -Recurse -Filter '*.java' | ForEach-Object { $sources.Add($_.FullName) }

Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $out | Out-Null
$sourceList = Join-Path $root 'build\oldml-sources.txt'
$sources | Set-Content -Encoding ASCII $sourceList

Write-Host ("javac {0} file(s) with {1} (--release 8; not compiled: {2})" -f $sources.Count, $Jdk, ($skipped -join ', '))
$classpath = ($modlauncher, $jopt, $asm, $asmTree, $asmCommons) -join ';'
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javac '-J-Duser.language=en' '-J-Duser.country=US' -encoding UTF-8 --release 8 -Xlint:-options `
    -cp $classpath -d $classes "@$sourceList"
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $previousPreference
if ($javacExit -ne 0) { throw "javac failed with exit code $javacExit" }

# ---- 1.13.2 obf -> runtime mapping (mcp_config config/joined.tsrg), shipped inside the artifact ----
Add-Type -AssemblyName System.IO.Compression.FileSystem
$mappingDir = Join-Path $classes 'optiforge\mappings'
New-Item -ItemType Directory -Force -Path $mappingDir | Out-Null
if (-not $McpConfig13) {
    # The Forge install lives at <rig>/downloads/<mc>-<forge>/mc; walk up and search every mcp_config tree
    # found (the 1.13.2 one is at the rig root, not under the 1.14.4 scratch install).
    $cursor = $ForgeHome14
    $mcpRoots = New-Object System.Collections.Generic.List[string]
    for ($depth = 0; $depth -lt 5 -and $cursor; $depth++) {
        $candidate = Join-Path $cursor 'libraries\de\oceanlabs\mcp\mcp_config'
        if ((Test-Path $candidate) -and -not $mcpRoots.Contains($candidate)) { $mcpRoots.Add($candidate) }
        $cursor = Split-Path $cursor -Parent
    }
    foreach ($mcpRoot in $mcpRoots) {
        $candidate = Get-ChildItem $mcpRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like '1.13.2-*' } |
            Sort-Object Name -Descending | Select-Object -First 1
        if (-not $candidate) { continue }
        $zipFile = Get-ChildItem $candidate.FullName -File -Filter '*.zip' | Select-Object -First 1
        if ($zipFile) { $McpConfig13 = $zipFile.FullName; break }
    }
}
if ($McpConfig13 -and (Test-Path $McpConfig13)) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($McpConfig13)
    try {
        $entry = $zip.GetEntry('config/joined.tsrg')
        if (-not $entry) { throw "config/joined.tsrg not found in $McpConfig13" }
        $target = Join-Path $mappingDir '1.13.2-obf-to-srg.tsrg'
        $source = $entry.Open()
        try {
            $outStream = [System.IO.File]::Create($target)
            try { $source.CopyTo($outStream) } finally { $outStream.Dispose() }
        } finally { $source.Dispose() }
    } finally { $zip.Dispose() }
    Write-Host ("1.13.2 map: {0} ({1} bytes)" -f (Split-Path $McpConfig13 -Leaf),
        (Get-Item (Join-Path $mappingDir '1.13.2-obf-to-srg.tsrg')).Length)
} else {
    Write-Warning 'no 1.13.2 mcp_config zip found: the oldml jar will not support 1.13.2 (pass -McpConfig13)'
}

# ---- resources: everything from src/main/resources except the service file, which must name the old entry ----
Copy-Item (Join-Path $resources '*') $classes -Recurse -Force
Remove-Item (Join-Path $classes 'META-INF\services\cpw.mods.modlauncher.api.ITransformationService') -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $classes 'META-INF\services') | Out-Null
$serviceFile = Join-Path $classes 'META-INF\services\cpw.mods.modlauncher.api.ITransformationService'
[System.IO.File]::WriteAllText($serviceFile,
    "kynarain.cn.optiforge.oldml.OldModLauncherService`n",
    (New-Object System.Text.UTF8Encoding($false)))

# Build identity, same shape as tools/build.ps1 (the runtime reads optiforge/version.txt).
New-Item -ItemType Directory -Force -Path (Join-Path $classes 'optiforge') | Out-Null
[System.IO.File]::WriteAllText((Join-Path $classes 'optiforge\version.txt'), $Version,
    (New-Object System.Text.UTF8Encoding($false)))
# 1.14-1.16 need no SRG->official remap; 1.13.2 ships only the obf->runtime joined.tsrg above.

$jar = Join-Path $out "OptiForge-$Version-oldml.jar"
$manifest = Join-Path $root 'build\MANIFEST-OLDML.MF'
$manifestText = "Manifest-Version: 1.0`r`n" +
    "Main-Class: kynarain.cn.optiforge.oldml.OldMlProbe`r`n" +
    "Implementation-Title: OptiForge (oldml, Java 8)`r`n" +
    "Implementation-Version: $Version`r`n" +
    "Implementation-Vendor: Kynarain`r`n`r`n"
[System.IO.File]::WriteAllText($manifest, $manifestText, (New-Object System.Text.ASCIIEncoding))
& $jarTool --create --file $jar --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host ("built {0} ({1} bytes)" -f $jar, (Get-Item $jar).Length)
