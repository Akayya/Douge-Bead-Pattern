# Boot emulator if needed, install the debug APK, launch it, optionally take a screenshot.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-run.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-run.ps1 -Shot screenshots\m1-home.png
# NOTE: keep this file ASCII-only - Windows PowerShell 5.1 reads un-BOM'd UTF-8 as ANSI.
param(
    [string]$Avd = 'DSH_Pixel7_API37',
    [string]$Package = 'com.pindou.app',
    [string]$Shot = ''
)
$ErrorActionPreference = 'Continue'

$root = Split-Path -Parent $PSScriptRoot
$sdk = 'D:\AS works'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emu = Join-Path $sdk 'emulator\emulator.exe'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path $apk)) { throw "APK not found, run scripts\build.ps1 first" }

function Adb {
    param([string[]]$Cmd)
    $r = & $adb @Cmd 2>&1
    return (($r | Out-String).Trim())
}

# 1. make sure an emulator is up and booted
$booted = $false
if ((Adb -Cmd @('devices')) -match 'emulator-\d+\s+device') {
    if ((Adb -Cmd @('shell','getprop','sys.boot_completed')) -match '1') { $booted = $true }
}
if (-not $booted) {
    Write-Host "[emu] starting $Avd ..."
    $emuArgs = @('-avd', $Avd, '-no-window', '-no-audio', '-no-boot-anim',
                 '-gpu', 'swiftshader_indirect', '-no-snapshot-save')
    Start-Process -FilePath $emu -ArgumentList $emuArgs -WindowStyle Hidden | Out-Null
    $deadline = (Get-Date).AddSeconds(300)
    do {
        Start-Sleep -Seconds 3
        $booted = ((Adb -Cmd @('shell','getprop','sys.boot_completed')) -match '1')
    } while (-not $booted -and (Get-Date) -lt $deadline)
    if (-not $booted) { throw "emulator did not finish booting within 300s" }
}
Write-Host "[emu] ready"

foreach ($k in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
    Adb -Cmd @('shell','settings','put','global', $k, '0') | Out-Null
}

# 2. install
Write-Host "[install] $apk"
$installOut = Adb -Cmd @('install','-r',$apk)
if ($installOut -notmatch 'Success') { throw "install failed: $installOut" }

# 3. launch
Write-Host "[launch] $Package"
$launchOut = Adb -Cmd @('shell','am','start','-W','-n',"$Package/.MainActivity")
Write-Host ($launchOut -replace "`r`n", ' ')
Start-Sleep -Seconds 2

# 4. screenshot
if ($Shot -ne '') {
    $out = Join-Path $root $Shot
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
    cmd.exe /c "`"$adb`" exec-out screencap -p > `"$out`""
    Write-Host "[shot] $out"
}


