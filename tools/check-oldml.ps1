# Static + runtime checks for build/libs-oldml/OptiForge-<ver>-oldml.jar (the Java-8 artifact for
# Forge 1.14.4 / 1.15.2 / 1.16.5).
#
# What is checked, and why each check exists:
#   * every class entry is class-file major 52: a Java 8 JVM rejects anything newer
#     ("Unsupported class file major version"), and that is the whole reason this artifact exists;
#   * the service file names the old-ModLauncher entry point, and the jar carries no module-info /
#     META-INF/versions entry (Java 8 ignores Multi-Release, and the oldml jar must not pretend);
#   * the service loads through ServiceLoader on a Java 8 runtime with each ModLauncher of the range
#     (4.1.0 / 5.1.2 / 8.1.3), and its abstract old-API methods are callable - this is the offline
#     equivalent of "Forge finds it" without launching a game;
#   * the shared sources still compile for the Java-17 artifact (tools/build.ps1), i.e. the Java-8
#     refactor did not break the modern build.
#
# Usage: pwsh -File tools/check-oldml.ps1 [-Jar <jar>] [-Jre8 <java.exe>] [-SkipJava17Build]
[CmdletBinding()]
param(
    [string]$Jar = '',
    [string]$Jre8 = 'C:\Program Files\Java\jre1.8.0_503\bin\java.exe',
    [switch]$SkipJava17Build
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$rig = 'I:\mods\optifineoforge-test'
$failures = New-Object System.Collections.Generic.List[string]

Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $Jar) {
    $version = (Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^mod_version_base=' }) -split '=' | Select-Object -Last 1
    $Jar = Join-Path $root "build\libs-oldml\OptiForge-$($version.Trim())-oldml.jar"
}
if (-not (Test-Path $Jar)) {
    throw "artifact not found: $Jar (run pwsh -File tools/build-oldml.ps1 first)"
}
$jarSize = (Get-Item $Jar).Length
Write-Host ("jar      : {0} ({1} bytes)" -f $Jar, $jarSize)

# ---- 1. class-file versions + forbidden entries ----
$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
$classCount = 0
$badVersions = New-Object System.Collections.Generic.List[string]
$forbidden = New-Object System.Collections.Generic.List[string]
foreach ($entry in $zip.Entries) {
    $name = $entry.FullName
    if ($name.StartsWith('META-INF/versions/') -or $name -eq 'module-info.class') {
        $forbidden.Add($name)
        continue
    }
    if (-not $name.EndsWith('.class')) { continue }
    $classCount++
    $stream = $entry.Open()
    $header = New-Object byte[] 8
    $read = $stream.Read($header, 0, 8)
    $stream.Dispose()
    if ($read -lt 8) { $badVersions.Add("$name (truncated)"); continue }
    $major = ($header[6] -shl 8) -bor $header[7]
    if ($major -ne 52) { $badVersions.Add("$name (major $major)") }
}
$zip.Dispose()
Write-Host ("classes  : {0} entries, all major 52: {1}" -f $classCount, ($badVersions.Count -eq 0))
if ($classCount -eq 0) { $failures.Add('no class entries') }
foreach ($bad in $badVersions) { $failures.Add("class-file version: $bad") }
foreach ($bad in $forbidden) { $failures.Add("forbidden entry: $bad") }

# ---- 2. service file ----
$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
$serviceEntry = $zip.GetEntry('META-INF/services/cpw.mods.modlauncher.api.ITransformationService')
$serviceText = $null
if ($serviceEntry) {
    $reader = New-Object System.IO.StreamReader($serviceEntry.Open())
    $serviceText = $reader.ReadToEnd().Trim()
    $reader.Dispose()
}
$zip.Dispose()
if (-not $serviceEntry) {
    $failures.Add('META-INF/services/cpw.mods.modlauncher.api.ITransformationService is missing')
} else {
    $expected = 'kynarain.cn.optiforge.oldml.OldModLauncherService'
    Write-Host ("service  : {0}" -f $serviceText)
    if ($serviceText -ne $expected) { $failures.Add("service file names '$serviceText', expected '$expected'") }
}

# ---- 2b. 1.13.2 obf -> runtime mapping shipped inside the artifact ----
$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
$mappingEntry = $zip.GetEntry('optiforge/mappings/1.13.2-obf-to-srg.tsrg')
$mappingLength = if ($mappingEntry) { $mappingEntry.Length } else { 0 }
$zip.Dispose()
if (-not $mappingEntry -or $mappingLength -lt 100000) {
    $failures.Add('optiforge/mappings/1.13.2-obf-to-srg.tsrg is missing or truncated in the artifact')
} else {
    Write-Host ("1.13.2 map: optiforge/mappings/1.13.2-obf-to-srg.tsrg ({0} bytes)" -f $mappingLength)
}

# ---- 3. Java 8 runtime probe against every ModLauncher of the range ----
if (-not (Test-Path $Jre8)) {
    $failures.Add("Java 8 runtime not found: $Jre8")
} else {
    $modlaunchers = @(
        @{ mc = '1.13.2'; path = Join-Path $rig 'downloads\1.13.2-25.0.219\mc\libraries\cpw\mods\modlauncher\2.1.1\modlauncher-2.1.1.jar' },
        @{ mc = '1.14.4'; path = Join-Path $rig 'downloads\1.14.4-28.2.26\mc\libraries\cpw\mods\modlauncher\4.1.0\modlauncher-4.1.0.jar' },
        @{ mc = '1.15.2'; path = Join-Path $rig 'downloads\1.15.2-31.2.57\mc\libraries\cpw\mods\modlauncher\5.1.2\modlauncher-5.1.2.jar' },
        @{ mc = '1.16.5'; path = Join-Path $rig 'downloads\1.16.5-36.2.39\mc\libraries\cpw\mods\modlauncher\8.1.3\modlauncher-8.1.3.jar' }
    )
    $jopt = Get-ChildItem (Join-Path $rig 'downloads\1.14.4-28.2.26\mc\libraries\net\sf\jopt-simple') -Recurse -File -Filter 'jopt-simple-*.jar' |
        Sort-Object FullName | Select-Object -First 1
    # Forge supplies ASM at runtime (1.14.4 ships 6.2, 1.16.5 ships 9.1); the artifact does not bundle it,
    # so the probe needs it on the classpath too, exactly like the launch does.
    $asmJars = Get-ChildItem (Join-Path $rig 'downloads\1.14.4-28.2.26\mc\libraries\org\ow2\asm') -Recurse -File -Filter '*.jar' |
        Where-Object { $_.Name -match '^asm-(6\.2|tree-6\.2|commons-6\.2)\.jar$' }
    foreach ($ml in $modlaunchers) {
        if (-not (Test-Path $ml.path)) {
            $failures.Add("ModLauncher for $($ml.mc) not installed: $($ml.path)")
            continue
        }
        $classpath = "$Jar;$($ml.path)"
        if ($jopt) { $classpath += ";$($jopt.FullName)" }
        foreach ($asm in $asmJars) { $classpath += ";$($asm.FullName)" }
        $output = & $Jre8 -cp $classpath kynarain.cn.optiforge.oldml.OldMlProbe 2>&1
        $result = $output | Where-Object { $_ -match '^RESULT' } | Select-Object -First 1
        $output | Where-Object { $_ -match '^(java|service|RESULT)' } | ForEach-Object { Write-Host ("  [{0}] {1}" -f $ml.mc, $_) }
        if (-not $result -or $result -notmatch 'OK') {
            $failures.Add("Java 8 probe failed against $($ml.mc) ModLauncher: $result")
        }
    }
}

# ---- 4. the shared sources still compile for the Java-17 artifact ----
if (-not $SkipJava17Build) {
    # build.ps1 writes javac notes (deprecated API) to stderr; under Stop that native stderr is
    # treated as a terminating error even when compilation succeeded, so judge by the exit code.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1') 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($code -ne 0) {
        $failures.Add("tools/build.ps1 failed with exit code $code")
    } else {
        $built = $output | Where-Object { $_ -match '^built ' } | Select-Object -Last 1
        if (-not $built) { $failures.Add("tools/build.ps1 produced no 'built' line") }
        else { Write-Host ("jdk17    : $built") }
    }
}

# ---- verdict ----
if ($failures.Count -gt 0) {
    Write-Host ''
    foreach ($failure in $failures) { Write-Host ("FAIL     : {0}" -f $failure) }
    Write-Host ("RESULT   : FAIL - {0} problem(s)" -f $failures.Count)
    exit 1
}
Write-Host ''
Write-Host ("RESULT   : OK - {0} classes major 52, 1.13.2 obf map present, service loads on Java 8 against ML 2.1.1/4.1.0/5.1.2/8.1.3, Java-17 build green" -f $classCount)
