param(
    [string]$TargetPackage = 'com.tencent.mm',
    [string]$ModulePackage = 'com.lincc.mdwechat',
    [string]$OutDir,
    [int]$LogLines = 6000,
    [string]$Filter = 'AndroidRuntime|FATAL EXCEPTION|LSPosed|Vector|Xposed|MDWechat|MDWechatModule|SecurityException|EACCES|ENOENT|NoSuchMethod|ClassNotFound|NoClassDefFound|ExceptionInInitializerError|com.tencent.mm'
)

. "$PSScriptRoot/common.ps1"

Assert-AdbDevice

if (-not $OutDir) {
    $OutDir = New-ArtifactDir -Kind 'captures'
} else {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $OutDir = (Resolve-Path -LiteralPath $OutDir).Path
}

$screenshot = Join-Path $OutDir 'screenshot.png'
$windowXml = Join-Path $OutDir 'window.xml'
$logcat = Join-Path $OutDir 'logcat.txt'
$filtered = Join-Path $OutDir 'logcat.filtered.txt'
$logcatHealth = Join-Path $OutDir 'logcat-health.txt'
$moduleState = Join-Path $OutDir 'module-state.txt'
$windowState = Join-Path $OutDir 'window-state.txt'

Invoke-Adb shell 'screencap -p /sdcard/codex-capture.png'
Invoke-Adb pull /sdcard/codex-capture.png $screenshot | Out-Null
Invoke-Adb shell 'rm -f /sdcard/codex-capture.png' | Out-Null

Invoke-Adb shell 'uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true'
$windowContent = Invoke-Adb exec-out cat /sdcard/window.xml
Set-Content -LiteralPath $windowXml -Value $windowContent -Encoding UTF8

$logContent = Invoke-Adb logcat -d -v threadtime -t $LogLines
Set-Content -LiteralPath $logcat -Value $logContent -Encoding UTF8
$filteredContent = Select-String -LiteralPath $logcat -Pattern $Filter | ForEach-Object { $_.Line }
Set-Content -LiteralPath $filtered -Value $filteredContent -Encoding UTF8

$healthLines = New-Object System.Collections.Generic.List[string]
function Add-HealthLines {
    param([object]$Value)
    foreach ($line in @($Value)) {
        $healthLines.Add([string]$line) | Out-Null
    }
}
$healthLines.Add("--- logcat -g ---") | Out-Null
Add-HealthLines (Invoke-Adb shell 'logcat -g 2>&1')
$healthLines.Add("--- logcat -b all -g ---") | Out-Null
Add-HealthLines (Invoke-Adb shell 'logcat -b all -g 2>&1')
$healthLines.Add("--- logd process ---") | Out-Null
Add-HealthLines (Invoke-Adb shell 'ps -A -o PID,USER,NAME,ARGS | grep -E "logd|logcat" | grep -v grep || true')
$healthLines.Add("--- log properties ---") | Out-Null
Add-HealthLines (Invoke-Adb shell 'getprop | grep -Ei "logd|logcat|persist.log|persist.sys.log|persist.vendor.logcontrol|ztelog|logger|debuggable|logcontrol" | head -160')
$healthLines | Set-Content -LiteralPath $logcatHealth -Encoding UTF8

$stateLines = New-Object System.Collections.Generic.List[string]
function Add-StateLines {
    param([object]$Value)
    foreach ($line in @($Value)) {
        $stateLines.Add([string]$line) | Out-Null
    }
}

$stateLines.Add("target=$TargetPackage")
$stateLines.Add("module=$ModulePackage")
$stateLines.Add("adb=$(Get-AdbPath)")
$stateLines.Add("--- device ---")
Add-StateLines (Invoke-Adb shell 'getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk')
$stateLines.Add("--- packages ---")
Add-StateLines (Invoke-Adb shell "dumpsys package $TargetPackage | grep -E 'versionName|versionCode|targetSdk|installerPackageName' || true")
$stateLines.Add("--- lsposed ---")
$cli = Get-LsposedCli
if ($cli) {
    Add-StateLines (Invoke-Adb shell "su -c '$cli status 2>/dev/null || true'")
    Add-StateLines (Invoke-Adb shell "su -c '$cli modules ls 2>/dev/null | grep $ModulePackage || true'")
    Add-StateLines (Invoke-Adb shell "su -c '$cli scope ls $ModulePackage 2>/dev/null || true'")
} else {
    $stateLines.Add('LSPosed CLI not available')
}
$stateLines | Set-Content -LiteralPath $moduleState -Encoding UTF8

Invoke-Adb shell 'dumpsys window windows 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp|Window #" || true' |
    Set-Content -LiteralPath $windowState -Encoding UTF8

Write-Output "capture-dir=$OutDir"
Write-Output "screenshot=$screenshot"
Write-Output "window=$windowXml"
Write-Output "filtered-log=$filtered"
Write-Output "logcat-health=$logcatHealth"
Write-Output "module-state=$moduleState"
