param(
    [string]$CaptureDir,
    [string]$WindowXml
)

. "$PSScriptRoot/common.ps1"

if (-not $WindowXml) {
    if (-not $CaptureDir) {
        $capturesRoot = Join-Path (Get-RepoRoot) '.codex-debug/captures'
        $latest = Get-ChildItem -LiteralPath $capturesRoot -Directory |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if (-not $latest) {
            throw "No capture directories found under $capturesRoot"
        }
        $CaptureDir = $latest.FullName
    }
    $WindowXml = Join-Path $CaptureDir 'window.xml'
}

$WindowXml = (Resolve-Path -LiteralPath $WindowXml).Path
[xml]$doc = Get-Content -Raw -LiteralPath $WindowXml
$nodes = @($doc.SelectNodes('//*'))

function Get-AttrValues {
    param([string]$Name)
    $nodes |
        ForEach-Object { $_.GetAttribute($Name) } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
}

function Test-RedpacketStrongSignal {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    if ($Text.Contains('微信红包') -or $Text.Contains('恭喜发财')) {
        return $true
    }
    if (-not $Text.Contains('红包')) {
        return $false
    }
    foreach ($marker in @('领取', '已领', '领完', '已过期', '红包封面')) {
        if ($Text.Contains($marker)) {
            return $true
        }
    }
    return $false
}

function Select-MatchedText {
    param([string[]]$Values, [string[]]$Patterns)
    $matchedValues = New-Object System.Collections.Generic.List[string]
    foreach ($value in $Values) {
        foreach ($pattern in $Patterns) {
            if ($value -match $pattern) {
                $matchedValues.Add($value) | Out-Null
                break
            }
        }
    }
    $matchedValues | Select-Object -Unique
}

function Test-AnyResourceId {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        if ($idSuffixes -contains $candidate) {
            return $true
        }
    }
    return $false
}

$texts = @(Get-AttrValues 'text')
$descs = @(Get-AttrValues 'content-desc')
$resourceIds = @(Get-AttrValues 'resource-id')
$allText = (@($texts) + @($descs)) -join "`n"
$idSuffixes = $resourceIds | ForEach-Object { ($_ -split '/')[-1] } | Select-Object -Unique

$redpacketStrong = Test-RedpacketStrongSignal $allText
$redpacketWeak = $allText.Contains('红包')
$transfer = $allText -match '微信转账|转账|请收款|已收款|待入账|已退还|已过期|已取消|¥|￥'
$miniProgram = $allText.Contains('小程序') -or (Test-AnyResourceId @('biq', 'biu', 'big', 'bit'))
$contactCard = ($allText -match '个人名片|名片') -or (Test-AnyResourceId @('bpv', 'br9'))
$locationCard = Test-AnyResourceId @('bp8', 'bp6', 'bp5')
$imageMessage = ($descs -contains '图片') -or ($idSuffixes -contains 'bkm')

Write-Output "window=$WindowXml"
Write-Output "redpacketStrong=$([bool]$redpacketStrong)"
Write-Output "redpacketWeakText=$([bool]$redpacketWeak)"
Write-Output "transferSignal=$([bool]$transfer)"
Write-Output "miniProgramSignal=$([bool]$miniProgram)"
Write-Output "contactCardSignal=$([bool]$contactCard)"
Write-Output "locationCardSignal=$([bool]$locationCard)"
Write-Output "imageMessageSignal=$([bool]$imageMessage)"

$matchedTexts = Select-MatchedText `
    -Values (@($texts) + @($descs)) `
    -Patterns @('红包', '恭喜发财', '转账', '收款', '小程序', '名片', '图片', '位置', '链接')

if ($matchedTexts) {
    Write-Output "--- matched text ---"
    $matchedTexts | Select-Object -First 40 | ForEach-Object { Write-Output $_ }
}

$interestingIds = $idSuffixes | Where-Object {
    $_ -in @(
        'a3u', 'a3m', 'a3y', 'a3o', 'a3n',
        'a48', 'a46', 'a44', 'a45', 'gbh',
        'biq', 'biu', 'big', 'bit',
        'bpv', 'br9',
        'bp8', 'bp6', 'bp5',
        'bjs', 'bjr', 'bjp', 'bjx', 'bju',
        'bkg', 'bkm', 'brr', 'brp', 'bkl'
    )
}

if ($interestingIds) {
    Write-Output "--- resource hints ---"
    $interestingIds | Sort-Object | ForEach-Object { Write-Output $_ }
}
