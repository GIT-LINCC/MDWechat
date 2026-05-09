param(
    [string]$TargetPackage = 'com.tencent.mm',
    [string]$OutDir,
    [int]$Seconds = 12,
    [string]$DeviceLog = '/data/local/tmp/codex-storage-trace.log',
    [string]$WatchArgs = '/mnt/pass_through/0/emulated/0/Android/data:ndmy /mnt/androidwritable/0/emulated/0/Android/data:ndmy /storage/emulated/0/Android/data:ndmy'
)

. "$PSScriptRoot/common.ps1"

Assert-AdbDevice

if (-not $OutDir) {
    $OutDir = New-ArtifactDir -Kind 'traces'
} else {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $OutDir = (Resolve-Path -LiteralPath $OutDir).Path
}

$traceFile = Join-Path $OutDir 'storage-events.txt'
$dirStateFile = Join-Path $OutDir 'dir-state.txt'
$pidFile = "$DeviceLog.pid"

$cleanupScript = "for pid in `$(ps -A -o PID,ARGS | grep `"$DeviceLog`" | grep -v grep | sed -n `"s/^ *\([0-9][0-9]*\).*/\1/p`"); do ps -A -o PID,PPID | while read p pp; do if [ `"`$pp`" = `"`$pid`" ]; then kill `"`$p`" 2>/dev/null || true; fi; done; kill `"`$pid`" 2>/dev/null || true; done; rm -f $pidFile"
Invoke-Adb shell "su -c '$cleanupScript'"

$startScript = "rm -f $DeviceLog; nohup sh -c 'date > $DeviceLog; inotifyd - $WatchArgs >> $DeviceLog 2>&1' >/dev/null 2>&1 &"
Invoke-Adb shell "su -c `"$startScript`""

Invoke-Adb logcat -c
Invoke-Adb shell "am force-stop $TargetPackage"
Start-Sleep -Seconds 1
Invoke-Adb shell "monkey -p $TargetPackage -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Seconds $Seconds

Invoke-Adb exec-out su -c "cat $DeviceLog 2>/dev/null" | Set-Content -LiteralPath $traceFile -Encoding UTF8

Invoke-Adb shell "su -c 'ls -ld /data/media/0/Android/data/$TargetPackage /mnt/pass_through/0/emulated/0/Android/data/$TargetPackage /storage/emulated/0/Android/data/$TargetPackage 2>&1 || true'" |
    Set-Content -LiteralPath $dirStateFile -Encoding UTF8

Invoke-Adb shell "su -c '$cleanupScript'" | Out-Null

Write-Output "trace-dir=$OutDir"
Write-Output "storage-events=$traceFile"
Write-Output "dir-state=$dirStateFile"
