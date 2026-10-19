# Fetches the compile-time jars OptiForge needs into build/deps.
#
# OptiForge compiles against ModLauncher and ASM only - never against Minecraft or Forge classes.
# That is deliberate: the same jar must load on 1.20.x, 1.21.x and 26.x, so the only API it may
# touch is the one ModLauncher itself defines. Forge ships ModLauncher and ASM at runtime; nothing
# from this directory is packaged into the mod.
#
# Usage: pwsh -File tools/fetch-deps.ps1 [-Force]

[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$deps = Join-Path $root 'build\deps'
New-Item -ItemType Directory -Force -Path $deps | Out-Null

# ModLauncher 10.0.9 is what Forge 1.20.x launches with (the 1.20.1 rig log says
# "ModLauncher 10.0.9+10.0.9+main.dcd20f30 starting"). It is the newest artifact on
# maven.minecraftforge.net, and the ITransformationService surface OptiForge uses has been stable
# since long before it; if a line ever needs a different API jar, override -ModLauncher.
$artifacts = @(
    @{ Name = 'modlauncher-10.0.9.jar';          Url = 'https://maven.minecraftforge.net/releases/cpw/mods/modlauncher/10.0.9/modlauncher-10.0.9.jar' },
    @{ Name = 'securejarhandler-2.1.10.jar';     Url = 'https://maven.minecraftforge.net/releases/cpw/mods/securejarhandler/2.1.10/securejarhandler-2.1.10.jar' },
    @{ Name = 'asm-9.9.1.jar';                   Url = 'https://repo1.maven.org/maven2/org/ow2/asm/asm/9.9.1/asm-9.9.1.jar' },
    @{ Name = 'asm-tree-9.9.1.jar';              Url = 'https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar' }
)

foreach ($a in $artifacts) {
    $target = Join-Path $deps $a.Name
    if ((Test-Path $target) -and -not $Force) {
        Write-Host ("have  {0} ({1} bytes)" -f $a.Name, (Get-Item $target).Length)
        continue
    }
    Write-Host "fetch $($a.Name)"
    Invoke-WebRequest -Uri $a.Url -OutFile $target -UseBasicParsing -TimeoutSec 120
    Write-Host ("      {0} bytes" -f (Get-Item $target).Length)
}
