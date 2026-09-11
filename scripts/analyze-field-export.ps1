#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,
    [string]$OutputPath,
    [securestring]$Passphrase,
    [int]$MinInterventionSamples = 20,
    [double]$MinTriggerSuccessRatePercent = 98,
    [long]$LatencyP95BudgetMs = 500,
    [int]$MaxHeartbeatGapMinutes = 30,
    [int]$MaxServiceConnections = 1,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

if ($MinInterventionSamples -lt 1) { throw 'MinInterventionSamples must be positive.' }
if ($MinTriggerSuccessRatePercent -lt 0 -or $MinTriggerSuccessRatePercent -gt 100) {
    throw 'MinTriggerSuccessRatePercent must be between 0 and 100.'
}
if ($LatencyP95BudgetMs -lt 0) { throw 'LatencyP95BudgetMs cannot be negative.' }
if ($MaxHeartbeatGapMinutes -lt 1) { throw 'MaxHeartbeatGapMinutes must be positive.' }
if ($MaxServiceConnections -lt 1) { throw 'MaxServiceConnections must be positive.' }

function Get-NearestRankPercentile {
    param(
        [Parameter(Mandatory = $true)]
        [long[]]$Values,
        [Parameter(Mandatory = $true)]
        [double]$Percentile
    )

    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [Math]::Ceiling($Percentile * $sorted.Count)
    return [long]$sorted[[Math]::Max(0, $rank - 1)]
}

function ConvertFrom-PauseEncryptedExport {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EnvelopeJson,
        [Parameter(Mandatory = $true)]
        [securestring]$SecurePassphrase
    )

    $envelope = $EnvelopeJson | ConvertFrom-Json
    if ($envelope.format -ne 'app.pausecn.encrypted-export' -or [int]$envelope.version -ne 1) {
        throw 'Unsupported encrypted export envelope.'
    }
    if ($envelope.kdf -ne 'PBKDF2WithHmacSHA256' -or $envelope.cipher -ne 'AES/GCM/NoPadding') {
        throw 'Unsupported export cryptography parameters.'
    }
    $iterations = [int]$envelope.iterations
    if ($iterations -lt 100000 -or $iterations -gt 1000000) {
        throw 'Export PBKDF2 iteration count is outside the accepted range.'
    }

    try {
        $salt = [Convert]::FromBase64String([string]$envelope.salt)
        $iv = [Convert]::FromBase64String([string]$envelope.iv)
        $cipherAndTag = [Convert]::FromBase64String([string]$envelope.ciphertext)
    } catch {
        throw 'Export envelope contains invalid Base64 data.'
    }
    if ($salt.Length -ne 16 -or $iv.Length -ne 12 -or $cipherAndTag.Length -le 16) {
        throw 'Export envelope has invalid salt, IV, or ciphertext lengths.'
    }

    $passwordPointer = [IntPtr]::Zero
    $plainPassword = $null
    $deriver = $null
    $aes = $null
    $key = $null
    $plainBytes = $null
    try {
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecurePassphrase)
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        if ([string]::IsNullOrEmpty($plainPassword)) {
            throw 'Export password cannot be empty.'
        }
        $deriver = [Security.Cryptography.Rfc2898DeriveBytes]::new(
            $plainPassword,
            $salt,
            $iterations,
            [Security.Cryptography.HashAlgorithmName]::SHA256
        )
        $key = $deriver.GetBytes(32)
        $cipherLength = $cipherAndTag.Length - 16
        $cipherBytes = [byte[]]::new($cipherLength)
        $tag = [byte[]]::new(16)
        [Array]::Copy($cipherAndTag, 0, $cipherBytes, 0, $cipherLength)
        [Array]::Copy($cipherAndTag, $cipherLength, $tag, 0, 16)
        $plainBytes = [byte[]]::new($cipherLength)
        $aes = [Security.Cryptography.AesGcm]::new($key, 16)
        $aes.Decrypt($iv, $cipherBytes, $tag, $plainBytes)
        return [Text.Encoding]::UTF8.GetString($plainBytes)
    } catch [Security.Cryptography.AuthenticationTagMismatchException] {
        throw 'Export password is incorrect or the file has been modified.'
    } finally {
        if ($null -ne $plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
        if ($null -ne $key) { [Array]::Clear($key, 0, $key.Length) }
        if ($null -ne $aes) { $aes.Dispose() }
        if ($null -ne $deriver) { $deriver.Dispose() }
        $plainPassword = $null
        if ($passwordPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        }
    }
}

$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path
$inputSize = (Get-Item -LiteralPath $resolvedInput).Length
if ($inputSize -le 0 -or $inputSize -gt 64MB) {
    throw 'Encrypted export must be between 1 byte and 64 MB.'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $directory = Split-Path -Parent $resolvedInput
    $baseName = [IO.Path]::GetFileNameWithoutExtension($resolvedInput)
    $OutputPath = Join-Path $directory "$baseName.field-report.json"
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
if ($resolvedOutput.Equals($resolvedInput, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output path must not overwrite the encrypted source export.'
}
if ((Test-Path -LiteralPath $resolvedOutput) -and -not $Force) {
    throw "Output already exists: $resolvedOutput. Pass -Force to replace it."
}
if ($null -eq $Passphrase) {
    $Passphrase = Read-Host '输入加密导出密码' -AsSecureString
}

$envelopeJson = Get-Content -Raw -LiteralPath $resolvedInput
$payloadJson = ConvertFrom-PauseEncryptedExport -EnvelopeJson $envelopeJson -SecurePassphrase $Passphrase
try {
    $payload = $payloadJson | ConvertFrom-Json
} finally {
    $payloadJson = $null
}
if ($payload.format -ne 'app.pausecn.local-data' -or [int]$payload.formatVersion -notin @(3, 4, 5, 6)) {
    throw 'This analyzer requires app.pausecn.local-data formatVersion 3, 4, 5 or 6.'
}

$events = @($payload.events | Where-Object { $null -ne $_ })
$successfulEvents = @($events | Where-Object { $_.outcome -ne 'DISPLAY_FAILED' })
$latencies = [long[]]@(
    $successfulEvents |
        Where-Object { $null -ne $_.triggerLatencyMs -and [long]$_.triggerLatencyMs -ge 0 } |
        ForEach-Object { [long]$_.triggerLatencyMs }
)
$triggerSuccessRate = if ($events.Count -eq 0) {
    $null
} else {
    [Math]::Round(100.0 * $successfulEvents.Count / $events.Count, 2)
}
$latencyP50 = Get-NearestRankPercentile -Values $latencies -Percentile 0.50
$latencyP95 = Get-NearestRankPercentile -Values $latencies -Percentile 0.95
$latencyMax = if ($latencies.Count -gt 0) { [long]($latencies | Measure-Object -Maximum).Maximum } else { $null }

$sessions = @($payload.serviceSessions | Where-Object { $null -ne $_ } | Sort-Object connectedAtEpochMs, id)
$heartbeatCount = [long](($sessions | Measure-Object -Property heartbeatCount -Sum).Sum)
$withinSessionGaps = [long[]]@($sessions | ForEach-Object { [long]$_.maxHeartbeatGapMs })
$crossSessionGaps = [System.Collections.Generic.List[long]]::new()
for ($index = 1; $index -lt $sessions.Count; $index++) {
    $previous = $sessions[$index - 1]
    $current = $sessions[$index]
    $previousBootEstimate = [long]$previous.connectedAtEpochMs - [long]$previous.connectedAtElapsedMs
    $currentBootEstimate = [long]$current.connectedAtEpochMs - [long]$current.connectedAtElapsedMs
    if ([Math]::Abs($currentBootEstimate - $previousBootEstimate) -le 120000) {
        $gap = [long]$current.connectedAtElapsedMs - [long]$previous.lastHeartbeatAtElapsedMs
        if ($gap -ge 0) { $crossSessionGaps.Add($gap) }
    }
}
$allHeartbeatGaps = [long[]]@($withinSessionGaps + $crossSessionGaps.ToArray())
$maxHeartbeatGapMs = if ($allHeartbeatGaps.Count -gt 0) {
    [long]($allHeartbeatGaps | Measure-Object -Maximum).Maximum
} else {
    $null
}
$zeroHeartbeatSessions = @($sessions | Where-Object { [int]$_.heartbeatCount -le 0 }).Count

$failures = [System.Collections.Generic.List[string]]::new()
$insufficient = [System.Collections.Generic.List[string]]::new()
if ($events.Count -lt $MinInterventionSamples) {
    $insufficient.Add("只有 $($events.Count) 次干预，少于最低样本数 $MinInterventionSamples。")
}
if ($latencies.Count -lt $MinInterventionSamples) {
    $insufficient.Add("只有 $($latencies.Count) 个有效显示延迟，少于最低样本数 $MinInterventionSamples。")
}
if ($sessions.Count -eq 0) {
    $insufficient.Add('没有服务连接会话。')
}
if ($null -ne $triggerSuccessRate -and $triggerSuccessRate -lt $MinTriggerSuccessRatePercent) {
    $failures.Add("触发成功率 $triggerSuccessRate% 低于 $MinTriggerSuccessRatePercent%。")
}
if ($null -ne $latencyP95 -and $latencyP95 -gt $LatencyP95BudgetMs) {
    $failures.Add("显示延迟 p95 ${latencyP95}ms 超过 ${LatencyP95BudgetMs}ms。")
}
$maxHeartbeatGapMsBudget = [long]$MaxHeartbeatGapMinutes * 60 * 1000
if ($null -ne $maxHeartbeatGapMs -and $maxHeartbeatGapMs -gt $maxHeartbeatGapMsBudget) {
    $failures.Add("最大心跳间隔 ${maxHeartbeatGapMs}ms 超过 $MaxHeartbeatGapMinutes 分钟。")
}
if ($sessions.Count -gt $MaxServiceConnections) {
    $failures.Add("服务连接 $($sessions.Count) 次，超过允许的 $MaxServiceConnections 次；请核对是否有计划内重启。")
}
if ($zeroHeartbeatSessions -gt 0) {
    $failures.Add("$zeroHeartbeatSessions 个服务会话没有成功持久化心跳。")
}

$status = if ($failures.Count -gt 0) {
    'FAIL'
} elseif ($insufficient.Count -gt 0) {
    'INSUFFICIENT_DATA'
} else {
    'PASS'
}
$report = [ordered]@{
    reportFormat = 'app.pausecn.field-quality-report'
    reportVersion = 1
    analyzedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    sourceFileSha256 = (Get-FileHash -LiteralPath $resolvedInput -Algorithm SHA256).Hash
    sourceExportedAtEpochMs = [long]$payload.exportedAtEpochMs
    sourceAppVersion = [string]$payload.appVersion
    status = $status
    criteria = [ordered]@{
        minInterventionSamples = $MinInterventionSamples
        minTriggerSuccessRatePercent = $MinTriggerSuccessRatePercent
        latencyP95BudgetMs = $LatencyP95BudgetMs
        maxHeartbeatGapMinutes = $MaxHeartbeatGapMinutes
        maxServiceConnections = $MaxServiceConnections
    }
    intervention = [ordered]@{
        attempts = $events.Count
        successfulDisplays = $successfulEvents.Count
        displayFailures = @($events | Where-Object { $_.outcome -eq 'DISPLAY_FAILED' }).Count
        triggerSuccessRatePercent = $triggerSuccessRate
        latencySamples = $latencies.Count
        latencyP50Ms = $latencyP50
        latencyP95Ms = $latencyP95
        latencyMaxMs = $latencyMax
    }
    service = [ordered]@{
        connections = $sessions.Count
        heartbeatObservations = $heartbeatCount
        maxHeartbeatGapMs = $maxHeartbeatGapMs
        sessionsWithoutHeartbeat = $zeroHeartbeatSessions
    }
    failures = @($failures)
    insufficientData = @($insufficient)
}

$outputDirectory = Split-Path -Parent $resolvedOutput
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedOutput -Encoding utf8
Write-Output "[$status] $resolvedOutput"
