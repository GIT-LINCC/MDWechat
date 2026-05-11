param(
    [int]$Port = 4173
)

$ErrorActionPreference = "Stop"

function Test-LoopbackPortAvailable {
    param([int]$CandidatePort)

    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $CandidatePort)
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($listener -ne $null) {
            $listener.Stop()
        }
    }
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$previewPath = Join-Path $repoRoot "webview\tablayout\index.html"
if (-not (Test-Path -LiteralPath $previewPath)) {
    throw "TabLayout preview page was not found: $previewPath"
}

$python = (Get-Command python -ErrorAction Stop).Source
$selectedPort = $Port
while (-not (Test-LoopbackPortAvailable -CandidatePort $selectedPort)) {
    $selectedPort += 1
}

$logDir = Join-Path $repoRoot ".codex-debug\servers"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stdoutLog = Join-Path $logDir "tablayout-preview-$selectedPort.out.log"
$stderrLog = Join-Path $logDir "tablayout-preview-$selectedPort.err.log"

$arguments = @(
    "-m",
    "http.server",
    "$selectedPort",
    "--bind",
    "127.0.0.1",
    "--directory",
    $repoRoot
)

$process = Start-Process `
    -FilePath $python `
    -ArgumentList $arguments `
    -WorkingDirectory $repoRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru

Start-Sleep -Milliseconds 500
if ($process.HasExited) {
    $errorText = if (Test-Path -LiteralPath $stderrLog) {
        Get-Content -Raw -LiteralPath $stderrLog
    } else {
        ""
    }
    throw "TabLayout preview server exited early. $errorText"
}

$url = "http://127.0.0.1:$selectedPort/webview/tablayout/index.html"
Write-Output "MDWechat TabLayout preview server is running."
Write-Output "PID: $($process.Id)"
Write-Output "URL: $url"
Write-Output "Logs: $stdoutLog"
