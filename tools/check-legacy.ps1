# Static checks for the 1.8-1.12.2 artifact, then the offline probe against real jars.
#
# What is checked, and why each check exists:
#   * manifest: FML 1.12.2 finds a coremod only through the FMLCorePlugin attribute, and the main class
#     makes the jar runnable as the offline probe;
#   * every class is class-file major 52: FML 1.12.2 parses mods/*.class with ASM 5.2, which rejects
#     anything newer ("Unsupported class file major version" on Java 17 bytecode);
#   * no class outside kynarain/cn/optiforge/legacy/** and no bundled ASM/Forge/launchwrapper classes:
#     the target runtime supplies those, and src/main must never leak into this artifact;
#   * no Java 9+ API in the sources (a class file can be 52 while calling a Java 11 method);
#   * the probe builds the real pack from the real 1.12.2 jars and prints the milestone line.
#
# Usage: pwsh -File tools/check-legacy.ps1 [-Jar <jar>] [-Jre8 <path>] [-ForgeHome <mc root>]
param(
    [string]$Jar = '',
    [string]$Jre8 = 'C:\Program Files\Java\jre1.8.0_503',
    [string]$ForgeHome = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = Split-Path -Parent $PSScriptRoot
if (-not $ForgeHome) { $ForgeHome = Join-Path $root 'build\phase3\forge-install-1.12.2\mc' }

$versionLine = Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^mod_version_base=' }
$version = ($versionLine -split '=')[1].Trim()
if (-not $Jar) { $Jar = Join-Path $root "build\libs-legacy\OptiForge-$version-legacy.jar" }

$failures = New-Object System.Collections.Generic.List[string]
function Fail($message) { $script:failures.Add($message); Write-Host "  FAIL $message" }
function Pass($message) { Write-Host "  ok   $message" }

if (-not (Test-Path $Jar)) {
    Write-Host "artifact missing, building it first ..."
    & (Join-Path $PSScriptRoot 'build-legacy.ps1') -Jre8 $Jre8 -ForgeHome $ForgeHome
}
if (-not (Test-Path $Jar)) { throw "artifact not found: $Jar" }
Write-Host "artifact: $Jar ($((Get-Item $Jar).Length) bytes)"

$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
try {
    $entries = @($zip.Entries)

    Write-Host '--- manifest ---'
    $manifestEntry = $entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' } | Select-Object -First 1
    if (-not $manifestEntry) {
        Fail 'META-INF/MANIFEST.MF missing'
    } else {
        $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
        $manifest = $reader.ReadToEnd()
        $reader.Dispose()
        $expect = @(
            'FMLCorePlugin: kynarain.cn.optiforge.legacy.OptiForgeLegacyCorePlugin',
            'Main-Class: kynarain.cn.optiforge.legacy.LegacyProbe',
            'Implementation-Title: OptiForge (legacy)',
            "Implementation-Version: $version",
            'Implementation-Vendor: Kynarain'
        )
        foreach ($line in $expect) {
            if ($manifest -match [regex]::Escape($line)) { Pass $line } else { Fail "manifest is missing: $line" }
        }
        if ($manifest -match 'ITransformationService') { Fail 'manifest mentions the ModLauncher service (wrong artifact)' }
    }

    Write-Host '--- contents ---'
    $classEntries = @($entries | Where-Object { $_.FullName -like '*.class' })
    if ($classEntries.Count -eq 0) { Fail 'no classes in the jar' }
    $foreign = @($classEntries | Where-Object {
        $_.FullName -notlike 'kynarain/cn/optiforge/legacy/*' })
    if ($foreign.Count -gt 0) {
        Fail ("classes outside kynarain/cn/optiforge/legacy: " + (($foreign | ForEach-Object { $_.FullName }) -join ', '))
    } else {
        Pass "$($classEntries.Count) classes, all in kynarain/cn/optiforge/legacy"
    }
    $bundled = @($entries | Where-Object {
        $_.FullName -like 'org/objectweb/asm/*' -or $_.FullName -like 'net/minecraftforge/*' -or
        $_.FullName -like 'net/minecraft/launchwrapper/*' -or
        $_.FullName -like 'META-INF/services/cpw.mods.modlauncher.api.ITransformationService' })
    if ($bundled.Count -gt 0) { Fail ('bundled runtime classes: ' + (($bundled | ForEach-Object { $_.FullName }) -join ', ')) }
    else { Pass 'no ASM/Forge/launchwrapper classes bundled, no ModLauncher service file' }
    if (-not ($entries | Where-Object { $_.FullName -eq 'optiforge/version.txt' })) {
        Fail 'optiforge/version.txt missing'
    } else {
        Pass 'optiforge/version.txt present'
    }

    Write-Host '--- class file version (must be 52, Java 8) ---'
    $bad = @()
    foreach ($entry in $classEntries) {
        $stream = $entry.Open()
        $buffer = New-Object byte[] 8
        try { [void]$stream.Read($buffer, 0, 8) } finally { $stream.Dispose() }
        $major = ([int]$buffer[6] -shl 8) -bor [int]$buffer[7]
        if ($major -ne 52) { $bad += "$($entry.FullName)=$major" }
    }
    if ($bad.Count -gt 0) { Fail ('non-52 class files: ' + (($bad | Select-Object -First 10) -join ', ')) }
    else { Pass "all $($classEntries.Count) classes are class-file major 52" }
} finally {
    $zip.Dispose()
}

Write-Host '--- source scan: Java 9+ API ---'
$patterns = @(
    @{ name = 'List.of/Set.of/Map.of'; regex = '\b(List|Set|Map)\.of\(' },
    @{ name = 'List/Set/Map.copyOf'; regex = '\b(List|Set|Map)\.copyOf\(' },
    @{ name = 'readAllBytes'; regex = '\.readAllBytes\(' },
    @{ name = 'readNBytes'; regex = '\.readNBytes\(' },
    @{ name = 'Files.writeString/readString'; regex = 'Files\.(writeString|readString)\(' },
    @{ name = 'getPlatformClassLoader'; regex = 'getPlatformClassLoader\(' },
    @{ name = 'String.isBlank/strip'; regex = '\.(isBlank|strip|stripLeading|stripTrailing)\(' },
    @{ name = 'var'; regex = '^\s*var\s+[A-Za-z_]' },
    @{ name = 'instanceof pattern'; regex = 'instanceof\s+[\w.$<>]+\s+[a-z]\w*\s*\)' },
    @{ name = 'switch arrow'; regex = '(case\s+[^:]+|default)\s*->' },
    @{ name = 'records/sealed'; regex = '\b(record|sealed|permits)\b' },
    @{ name = 'java.net.http'; regex = 'java\.net\.http' }
)
$sources = Get-ChildItem (Join-Path $root 'src\legacy\java') -Recurse -Filter '*.java'
$violations = @()
foreach ($source in $sources) {
    foreach ($pattern in $patterns) {
        $hit = Select-String -Path $source.FullName -Pattern $pattern.regex -AllMatches -ErrorAction SilentlyContinue
        foreach ($line in $hit) {
            # "case 1:" and comments are fine; only the arrow form is flagged
            $violations += "$($source.Name):$($line.LineNumber) [$($pattern.name)] $($line.Line.Trim())"
        }
    }
}
if ($violations.Count -gt 0) {
    foreach ($violation in ($violations | Select-Object -First 10)) { Fail "Java 9+ API: $violation" }
} else {
    Pass "no Java 9+ API in $($sources.Count) legacy sources"
}

Write-Host '--- probe (offline, real jars) ---'
$java8 = Join-Path $Jre8 'bin\java.exe'
$optifine = Join-Path $root 'build\optifine-pristine\OptiFine_1.12.2_HD_U_G5.jar'
$gamedir = Join-Path $root 'build\phase3\packtest'
if (-not (Test-Path $optifine)) {
    Fail "OptiFine jar missing: $optifine"
} elseif (-not (Test-Path $java8)) {
    Fail "JRE 8 not found: $Jre8"
} else {
    $output = & $java8 -cp $Jar kynarain.cn.optiforge.legacy.LegacyProbe --optifine $optifine --forge-home $ForgeHome --gamedir $gamedir 2>&1
    $exit = $LASTEXITCODE
    $output | ForEach-Object { Write-Host "  $_" }
    if ($exit -ne 0) { Fail "probe exited with $exit" }
    $milestone = ($output | Select-String -Pattern 'layout=flat / patches .* / RESULT: OK').Matches | Select-Object -First 1
    if (-not $milestone) {
        Fail 'probe did not print the milestone line with RESULT: OK'
    } else {
        $text = $milestone.Value
        $m = [regex]::Match($text, 'patches (\d+) / md5 (\d+) OK / own (\d+) / SRG (\d+) / drift (\d+)')
        if (-not $m.Success) {
            Fail "milestone line not parseable: $text"
        } else {
            $patches = [int]$m.Groups[1].Value
            $md5 = [int]$m.Groups[2].Value
            $own = [int]$m.Groups[3].Value
            $srg = [int]$m.Groups[4].Value
            $drift = [int]$m.Groups[5].Value
            if ($patches -le 0) { Fail 'no patches applied' } else { Pass "patches=$patches" }
            if ($md5 -ne $patches) { Fail "md5 ok ($md5) != patches ($patches)" } else { Pass "md5 $md5/$patches" }
            if ($own -lt 400) { Fail "unexpectedly few OptiFine own classes: $own" } else { Pass "own classes=$own" }
            if ($srg -ne 0) { Fail "own classes still carry $srg SRG tokens" } else { Pass 'SRG tokens=0' }
            if ($drift -lt 1) { Fail "no drift class measured (expected >0 on G5 vs Forge 2860)" }
            else { Pass "drift classes=$drift" }
        }
    }
    $coremodLine = $output | Select-String -Pattern '^coremod\s*:\s*OK'
    if (-not $coremodLine) { Fail 'probe could not load the coremod against Forge 1.12.2 + ASM 5.2' }
    else { Pass 'coremod loads with Forge/launchwrapper/ASM 5.2; merge validated with ASM 5.2' }

    Write-Host '--- staged classpath jar ---'
    $versionLine = Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^mod_version_base=' }
    $staged = Join-Path $gamedir "mods\OptiForge-$($versionLine.Split('=')[1].Trim())-optifine.jar"
    if (-not (Test-Path $staged)) {
        Fail "staged jar missing: $staged"
    } else {
        $szip = [System.IO.Compression.ZipFile]::OpenRead($staged)
        try {
            $sentries = @($szip.Entries)
            $own = @($sentries | Where-Object { $_.FullName -like 'net/optifine/*' })
            $assets = @($sentries | Where-Object { $_.FullName -like 'assets/*' })
            if ($own.Count -lt 400) { Fail "staged jar carries only $($own.Count) net/optifine classes" }
            else { Pass "staged jar: $($own.Count) net/optifine classes" }
            if ($assets.Count -lt 50) { Fail "staged jar carries only $($assets.Count) assets" }
            else { Pass "staged jar: $($assets.Count) assets" }
            if (-not ($sentries | Where-Object { $_.FullName -eq 'pack.mcmeta' })) { Fail 'staged jar has no pack.mcmeta' }
            else { Pass 'staged jar has pack.mcmeta' }
            # OptiFine 1.12.2 ships its own Config class in the default package and net/optifine/** references
            # it as bare "Config"; it must be staged or the crash reporter dies with NoClassDefFoundError.
            if (-not ($sentries | Where-Object { $_.FullName -eq 'Config.class' })) {
                Fail 'staged jar is missing root Config.class (net/optifine references bare Config)'
            } else { Pass 'staged jar has root Config.class' }
            # awu$Builder is a game class the payload ships as a complete file: it must go through the
            # transformer, not the class path.
            if ($sentries | Where-Object { $_.FullName -eq 'awu$Builder.class' }) {
                Fail 'staged jar contains awu$Builder.class (complete override must go through the transformer)'
            } else { Pass 'awu$Builder delivered via the transformer, not staged' }
            # A patched game class on the class path would be fed to FML's PatchingTransformer, whose adler32
            # check only accepts untouched vanilla bytes and calls System.exit(1) otherwise. Compare against
            # the real client jar instead of a name pattern, so OptiFine's own default-package classes pass.
            $baseJar = Join-Path $ForgeHome 'versions\1.12.2\1.12.2.jar'
            if (-not (Test-Path $baseJar)) {
                Fail "base jar for the staged-jar check not found: $baseJar"
            } else {
                $baseZip = [System.IO.Compression.ZipFile]::OpenRead($baseJar)
                try {
                    $baseNames = @($baseZip.Entries | ForEach-Object { $_.FullName })
                } finally {
                    $baseZip.Dispose()
                }
                $gameClasses = @($sentries | Where-Object { $_.FullName -like '*.class' -and $baseNames -contains $_.FullName })
                if ($gameClasses.Count -gt 0) {
                    Fail ('staged jar contains game classes (must go through the transformer only): ' +
                        (($gameClasses | ForEach-Object { $_.FullName }) -join ', '))
                } else { Pass 'staged jar contains no game class (checked against versions/1.12.2/1.12.2.jar)' }
            }
        } finally {
            $szip.Dispose()
        }
    }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "RESULT : FAIL - $($failures.Count) problem(s)"
    exit 1
}
Write-Host 'RESULT : OK - legacy artifact is Java 8 bytecode, FMLCorePlugin present, probe green'
