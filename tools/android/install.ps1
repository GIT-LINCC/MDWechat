param(
    [string]$TargetPackage = 'com.tencent.mm',
    [string]$ModulePackage = 'com.lincc.mdwechat',
    [string]$BuildTask = ':app:assembleDebug',
    [string]$ApkPath = 'app/build/outputs/apk/debug/app-debug.apk',
    [switch]$NoBuild,
    [switch]$NoInstall,
    [switch]$NoLsposed,
    [switch]$NoLaunch
)

. "$PSScriptRoot/common.ps1"

$root = Get-ProjectRoot
Set-Location $root
Assert-AdbDevice

if (-not $NoBuild) {
    $gradlew = Join-Path $root 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradlew)) {
        throw "Gradle wrapper not found: $gradlew"
    }
    & $gradlew $BuildTask
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
}

if (-not $NoInstall) {
    $resolvedApk = Resolve-Path -LiteralPath (Join-Path $root $ApkPath) -ErrorAction Stop
    Invoke-Adb install -r $resolvedApk.Path
    if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE" }
}

if (-not $NoLsposed) {
    $versionCode = Get-LsposedVersionCode
    $cli = Get-LsposedCli
    if ($cli -and $versionCode -ge 3043) {
        Invoke-Adb shell "su -c '$cli modules enable $ModulePackage'"
        Invoke-Adb shell "su -c '$cli scope add $ModulePackage $TargetPackage/0 >/dev/null 2>&1 || true'"
    } else {
        Write-Warning 'LSPosed/Vector CLI unavailable or too old; enable module and scope manually.'
    }
}

if (-not $NoLaunch) {
    Invoke-Adb shell "am force-stop $TargetPackage"
    Start-Sleep -Seconds 1
    Invoke-Adb shell "monkey -p $TargetPackage -c android.intent.category.LAUNCHER 1"
}

Write-Output "install-run complete: module=$ModulePackage target=$TargetPackage"
