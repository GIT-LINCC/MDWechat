param(
    [Parameter(Mandatory = $true)]
    [string]$LogFile,

    [string]$Pattern = 'AndroidRuntime|FATAL EXCEPTION|LSPosed|Vector|Xposed|MDWechat|MDWechatModule|SecurityException|EACCES|ENOENT|NoSuchMethod|ClassNotFound|NoClassDefFound|ExceptionInInitializerError',

    [int]$Context = 4,

    [int]$MaxMatches = 80
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $LogFile)) {
    throw "Log file not found: $LogFile"
}

$lines = Get-Content -LiteralPath $LogFile
$matchIndexes = New-Object System.Collections.Generic.List[int]

for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match $Pattern) {
        $matchIndexes.Add([int]$i)
        if ($matchIndexes.Count -ge $MaxMatches) { break }
    }
}

if ($matchIndexes.Count -eq 0) {
    Write-Output "No matches for pattern: $Pattern"
    exit 0
}

$emitted = New-Object System.Collections.Generic.HashSet[int]
foreach ($match in $matchIndexes) {
    $start = [Math]::Max(0, $match - $Context)
    $end = [Math]::Min($lines.Count - 1, $match + $Context)
    for ($i = $start; $i -le $end; $i++) {
        if ($emitted.Add([int]$i)) {
            '{0,6}: {1}' -f ($i + 1), $lines[$i]
        }
    }
    Write-Output ''
}
