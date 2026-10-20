# Builds build/libs/OptiForge-<version>.jar with javac + jar, without Gradle.
#
# Gradle (build.gradle) is the canonical build, but it needs a 130 MB distribution download on a
# first run; this script is the same thing in ten lines and is what the repository's own checks run.
#
# Usage: pwsh -File tools/build.ps1 [-Jdk <path>]

[CmdletBinding()]
param(
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }),
    [string]$Version = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$deps = Join-Path $root 'build\deps'
$classes = Join-Path $root 'build\classes'
$libs = Join-Path $root 'build\libs'

# The version comes from gradle.properties so this build and the Gradle build can never disagree.
if (-not $Version) {
    $line = Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^mod_version_base=' }
    if ($line) { $Version = ($line -split '=')[1].Trim() }
    if (-not $Version) { throw 'mod_version_base is not set in gradle.properties' }
}

if (-not (Test-Path (Join-Path $deps 'modlauncher-10.0.9.jar'))) {
    & (Join-Path $PSScriptRoot 'fetch-deps.ps1')
}

$javac = Join-Path $Jdk 'bin\javac.exe'
$jarTool = Join-Path $Jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "javac not found under $Jdk (pass -Jdk)" }

Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libs | Out-Null

# Compile against ModLauncher/ASM only, targeting Java 17 so one jar loads on every line
# (1.20.x runs Java 17, 1.21.x 21, 26.x 25 - class file 61 is accepted by all of them).
$classpath = (Get-ChildItem $deps -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

Write-Host "javac $($sources.Count) file(s) with $Jdk"
# javac writes notes (deprecated API, unchecked) to stderr; with the default Stop preference that aborted
# the build even though compilation succeeded. Judge by the exit code only.
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javac -encoding UTF-8 --release 17 -Xlint:-options -cp $classpath -d $classes @sources
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $previousPreference
if ($javacExit -ne 0) { throw "javac failed with exit code $javacExit" }

Copy-Item (Join-Path $root 'src\main\resources\*') $classes -Recurse -Force
# The SRG->official tables live in data/ (source of truth) and are shipped as resources, so the jar
# carries whatever the last tools/build-srg-map.ps1 produced.
$mappings = Join-Path $root 'data\mappings'
if (Test-Path $mappings) {
    $target = Join-Path $classes 'optiforge\mappings'
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Copy-Item (Join-Path $mappings '*.tsv') $target -Force
    # index.txt lets the runtime find the tables without listing jar entries; one line per MC version.
    $index = Get-ChildItem (Join-Path $mappings '*.tsv') | ForEach-Object { $_.BaseName -replace '-srg-to-official$', '' }
    [System.IO.File]::WriteAllLines((Join-Path $target 'index.txt'), $index, (New-Object System.Text.UTF8Encoding($false)))
}

# The runtime reads its own version from this file (see Diagnose.readVersion).
New-Item -ItemType Directory -Force -Path (Join-Path $classes 'optiforge') | Out-Null
[System.IO.File]::WriteAllText((Join-Path $classes 'optiforge\version.txt'), $Version, (New-Object System.Text.UTF8Encoding($false)))

$out = Join-Path $libs "OptiForge-$Version.jar"
# Manifest carries the same identity Forge's Mods screen shows: title, version, vendor (author).
$manifest = Join-Path $root ('build\MANIFEST.MF')
$manifestText = "Manifest-Version: 1.0`r`nMain-Class: kynarain.cn.optiforge.Diagnose`r`nImplementation-Title: OptiForge`r`nImplementation-Version: $Version`r`nImplementation-Vendor: Kynarain`r`n`r`n"
[System.IO.File]::WriteAllText($manifest, $manifestText, (New-Object System.Text.ASCIIEncoding))
& $jarTool --create --file $out --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host ("built {0} ({1} bytes)" -f $out, (Get-Item $out).Length)
