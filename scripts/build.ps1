# 构建 Debug APK
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'D:\Android\jbr'
Set-Location $root
& .\gradlew.bat assembleDebug --console=plain
exit $LASTEXITCODE
