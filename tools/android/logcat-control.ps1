param(
    [ValidateSet('Status', 'Enable', 'Disable', 'Probe')]
    [string]$Action = 'Status',

    [int]$WaitSeconds = 2
)

. "$PSScriptRoot/common.ps1"

Assert-AdbDevice

function Show-LogControlState {
    Write-Output "--- device ---"
    Invoke-Adb shell 'getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release'

    Write-Output "--- logcontrol props ---"
    Invoke-Adb shell 'echo init.svc.logcontrol=$(getprop init.svc.logcontrol); echo persist.sys.logcontrol.run=$(getprop persist.sys.logcontrol.run); echo persist.vendor.logcontrol.run=$(getprop persist.vendor.logcontrol.run); echo persist.sys.ztelog.enable=$(getprop persist.sys.ztelog.enable)'

    Write-Output "--- logcat -g ---"
    Invoke-Adb shell 'logcat -g'

    Write-Output "--- logcat -b all -g ---"
    Invoke-Adb shell 'logcat -b all -g'
}

function Invoke-LogcatProbe {
    $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
    $message = "codex-logcat-probe-$stamp"

    Invoke-Adb shell "log -t CodexLogcatProbe $message" | Out-Null
    Start-Sleep -Seconds 1

    Write-Output "--- probe result ---"
    Invoke-Adb shell "logcat -d -v threadtime -s CodexLogcatProbe | grep $message || true"
}

switch ($Action) {
    'Enable' {
        Write-Output "--- enabling Nubia/ZTE logcontrol ---"
        Invoke-AdbRootShell 'setprop persist.sys.logcontrol.run 1; setprop persist.vendor.logcontrol.run 1; setprop persist.sys.ztelog.enable 1; setprop ctl.start logcontrol' | Out-Null
        Start-Sleep -Seconds $WaitSeconds
        Show-LogControlState
        Invoke-LogcatProbe
    }
    'Disable' {
        Write-Output "--- disabling Nubia/ZTE logcontrol ---"
        Invoke-AdbRootShell 'setprop persist.sys.logcontrol.run 0; setprop persist.vendor.logcontrol.run 0; setprop persist.sys.ztelog.enable 0; setprop ctl.start logcontrol' | Out-Null
        Start-Sleep -Seconds $WaitSeconds
        Show-LogControlState
    }
    'Probe' {
        Show-LogControlState
        Invoke-LogcatProbe
    }
    default {
        Show-LogControlState
    }
}
