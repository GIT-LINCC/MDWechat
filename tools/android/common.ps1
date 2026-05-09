$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-ProjectRoot {
    $scriptDir = Split-Path -Parent $PSScriptRoot
    return (Resolve-Path -LiteralPath (Join-Path $scriptDir '..')).Path
}

function Get-AdbPath {
    $known = 'C:\Users\lcc\AppData\Local\Android\Sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $known -PathType Leaf) {
        return $known
    }

    if ($env:ADB) {
        if (Test-Path -LiteralPath $env:ADB -PathType Leaf) {
            return (Resolve-Path -LiteralPath $env:ADB).Path
        }
        if (Test-Path -LiteralPath $env:ADB -PathType Container) {
            $fromDir = Join-Path $env:ADB 'adb.exe'
            if (Test-Path -LiteralPath $fromDir -PathType Leaf) {
                return (Resolve-Path -LiteralPath $fromDir).Path
            }
        }
    }

    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    throw 'adb not found. Set $env:ADB or install Android platform-tools.'
}

function Invoke-Adb {
    $adb = Get-AdbPath
    & $adb @args
}

function Invoke-AdbShell {
    param([Parameter(Mandatory = $true)][string]$Command)
    Invoke-Adb shell $Command
}

function Invoke-AdbRootShell {
    param([Parameter(Mandatory = $true)][string]$Command)
    Invoke-Adb shell "su -c '$Command'"
}

function Get-RunStamp {
    return (Get-Date).ToString('yyyyMMdd-HHmmss')
}

function New-ArtifactDir {
    param(
        [string]$Kind = 'captures',
        [string]$Stamp = (Get-RunStamp)
    )
    $root = Get-ProjectRoot
    $dir = Join-Path $root ".codex-debug/$Kind/$Stamp"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return (Resolve-Path -LiteralPath $dir).Path
}

function Get-LsposedCli {
    $path = '/data/adb/lspd/cli'
    $probe = Invoke-Adb shell "su -c 'test -x $path && echo ok || true'"
    if (($probe -join "`n").Trim() -eq 'ok') {
        return $path
    }
    return $null
}

function Get-LsposedVersionCode {
    $cli = Get-LsposedCli
    if (-not $cli) { return $null }

    $status = Invoke-Adb shell "su -c '$cli status --json 2>/dev/null || $cli status 2>/dev/null || true'"
    $text = $status -join "`n"
    if ($text -match '"Version Code"\s*:\s*(\d+)') { return [int]$Matches[1] }
    if ($text -match 'Version Code:\s*(\d+)') { return [int]$Matches[1] }
    return $null
}

function Assert-AdbDevice {
    $devices = Invoke-Adb devices
    $ready = $devices | Where-Object { $_ -match "`tdevice$" }
    if (-not $ready) {
        throw "No adb device in 'device' state.`n$($devices -join "`n")"
    }
}
