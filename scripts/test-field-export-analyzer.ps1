[CmdletBinding()]
param()

#requires -Version 7.0

$ErrorActionPreference = 'Stop'
$analyzer = Join-Path $PSScriptRoot 'analyze-field-export.ps1'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("pausecn-field-analysis-" + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null

function New-TestEnvelope {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Payload,
        [Parameter(Mandatory = $true)]
        [string]$Password
    )

    $salt = [byte[]](0..15)
    $iv = [byte[]](16..27)
    $plainBytes = [Text.Encoding]::UTF8.GetBytes($Payload)
    # Intentionally exercises the legacy v1 work factor; the analyzer must keep old exports readable.
    $deriver = [Security.Cryptography.Rfc2898DeriveBytes]::new(
        $Password,
        $salt,
        210000,
        [Security.Cryptography.HashAlgorithmName]::SHA256
    )
    $key = $deriver.GetBytes(32)
    $cipherBytes = [byte[]]::new($plainBytes.Length)
    $tag = [byte[]]::new(16)
    $aes = [Security.Cryptography.AesGcm]::new($key, 16)
    try {
        $aes.Encrypt($iv, $plainBytes, $cipherBytes, $tag)
        $cipherAndTag = [byte[]]($cipherBytes + $tag)
        return [ordered]@{
            format = 'app.pausecn.encrypted-export'
            version = 1
            kdf = 'PBKDF2WithHmacSHA256'
            iterations = 210000
            cipher = 'AES/GCM/NoPadding'
            salt = [Convert]::ToBase64String($salt)
            iv = [Convert]::ToBase64String($iv)
            ciphertext = [Convert]::ToBase64String($cipherAndTag)
        } | ConvertTo-Json -Compress
    } finally {
        [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        [Array]::Clear($key, 0, $key.Length)
        $aes.Dispose()
        $deriver.Dispose()
    }
}

try {
    $events = [System.Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt 25; $index++) {
        $events.Add([ordered]@{
            id = $index + 1
            packageName = 'example.target'
            appLabel = '示例目标'
            occurredAtEpochMs = 1000 + $index
            outcome = if ($index -eq 24) { 'DISPLAY_FAILED' } else { 'CONTINUED' }
            purpose = '搜资料'
            triggerLatencyMs = 100 + 5 * $index
        })
    }
    $payload = [ordered]@{
        format = 'app.pausecn.local-data'
        formatVersion = 3
        exportedAtEpochMs = 123456789
        appVersion = 'field-test'
        settings = [ordered]@{}
        targets = @()
        events = @($events)
        serviceSessions = @(
            [ordered]@{
                id = 1
                connectedAtEpochMs = 1000000
                connectedAtElapsedMs = 10000
                lastHeartbeatAtEpochMs = 4600000
                lastHeartbeatAtElapsedMs = 3610000
                heartbeatCount = 5
                maxHeartbeatGapMs = 900000
            }
        )
    } | ConvertTo-Json -Depth 8 -Compress

    $password = 'field-test-password'
    $inputPath = Join-Path $temporaryRoot 'synthetic.pausecn.json'
    $outputPath = Join-Path $temporaryRoot 'report.json'
    New-TestEnvelope -Payload $payload -Password $password |
        Set-Content -LiteralPath $inputPath -Encoding utf8
    $securePassword = ConvertTo-SecureString $password -AsPlainText -Force

    & $analyzer `
        -InputPath $inputPath `
        -OutputPath $outputPath `
        -Passphrase $securePassword `
        -MinTriggerSuccessRatePercent 95 `
        -Force | Out-Null

    $reportText = Get-Content -Raw -LiteralPath $outputPath
    $report = $reportText | ConvertFrom-Json
    if ($report.status -ne 'PASS') { throw "Expected PASS, got $($report.status)." }
    if ([double]$report.intervention.triggerSuccessRatePercent -ne 96) {
        throw "Expected 96% trigger success, got $($report.intervention.triggerSuccessRatePercent)."
    }
    if ([long]$report.intervention.latencyP50Ms -ne 155 -or
        [long]$report.intervention.latencyP95Ms -ne 210
    ) {
        throw 'Unexpected p50/p95 latency calculation.'
    }
    if ([long]$report.service.heartbeatObservations -ne 5 -or
        [long]$report.service.maxHeartbeatGapMs -ne 900000
    ) {
        throw 'Unexpected service heartbeat summary.'
    }
    if ($reportText.Contains('example.target') -or $reportText.Contains('搜资料')) {
        throw 'Aggregate report leaked event-level package or purpose data.'
    }

    $privateMarker = 'AI-PRIVATE-MEMORY-MUST-NOT-APPEAR'
    $payloadV4 = $payload | ConvertFrom-Json
    $payloadV4.formatVersion = 4
    $payloadV4 | Add-Member -NotePropertyName includedCategories -NotePropertyValue @('base', 'memories', 'conversations')
    $payloadV4 | Add-Member -NotePropertyName ai -NotePropertyValue ([ordered]@{
        memories = @([ordered]@{
            text = $privateMarker
            kind = 'PREFERENCE'
            independent = $true
        })
        conversations = @([ordered]@{
            role = 'USER'
            text = 'AI-PRIVATE-CONVERSATION-MUST-NOT-APPEAR'
        })
    })
    $v4InputPath = Join-Path $temporaryRoot 'synthetic-v4.pausecn.json'
    $v4OutputPath = Join-Path $temporaryRoot 'report-v4.json'
    New-TestEnvelope -Payload ($payloadV4 | ConvertTo-Json -Depth 8 -Compress) -Password $password |
        Set-Content -LiteralPath $v4InputPath -Encoding utf8

    & $analyzer `
        -InputPath $v4InputPath `
        -OutputPath $v4OutputPath `
        -Passphrase $securePassword `
        -MinTriggerSuccessRatePercent 95 `
        -Force | Out-Null

    $v4ReportText = Get-Content -Raw -LiteralPath $v4OutputPath
    $v4Report = $v4ReportText | ConvertFrom-Json
    foreach ($section in @('criteria', 'intervention', 'service', 'failures', 'insufficientData')) {
        $v3Value = $report.$section | ConvertTo-Json -Depth 8 -Compress
        $v4Value = $v4Report.$section | ConvertTo-Json -Depth 8 -Compress
        if ($v4Value -ne $v3Value) {
            throw "Payload v4 changed base analysis section '$section'."
        }
    }
    if ($v4Report.status -ne $report.status) {
        throw 'Payload v4 changed the base analysis status.'
    }
    if ($v4ReportText.Contains($privateMarker) -or
        $v4ReportText.Contains('AI-PRIVATE-CONVERSATION-MUST-NOT-APPEAR') -or
        $v4ReportText.Contains('memories') -or
        $v4ReportText.Contains('conversations')
    ) {
        throw 'Aggregate v4 report leaked optional AI private fields.'
    }

    $reportMarker = 'REPORT-PRIVATE-INTERPRETATION-MUST-NOT-APPEAR'
    $payloadV5 = $payload | ConvertFrom-Json
    $payloadV5.formatVersion = 5
    $payloadV5 | Add-Member -NotePropertyName includedCategories -NotePropertyValue @('base', 'reports', 'report_interpretations')
    $payloadV5 | Add-Member -NotePropertyName reports -NotePropertyValue @([ordered]@{
        startDate = '2026-09-08'
        endDateExclusive = '2026-09-09'
        privateAiInterpretation = [ordered]@{
            observations = @($reportMarker)
            suggestion = 'REPORT-PRIVATE-SUGGESTION-MUST-NOT-APPEAR'
        }
    })
    $v5InputPath = Join-Path $temporaryRoot 'synthetic-v5.pausecn.json'
    $v5OutputPath = Join-Path $temporaryRoot 'report-v5.json'
    New-TestEnvelope -Payload ($payloadV5 | ConvertTo-Json -Depth 8 -Compress) -Password $password |
        Set-Content -LiteralPath $v5InputPath -Encoding utf8

    & $analyzer `
        -InputPath $v5InputPath `
        -OutputPath $v5OutputPath `
        -Passphrase $securePassword `
        -MinTriggerSuccessRatePercent 95 `
        -Force | Out-Null

    $v5ReportText = Get-Content -Raw -LiteralPath $v5OutputPath
    $v5Report = $v5ReportText | ConvertFrom-Json
    foreach ($section in @('criteria', 'intervention', 'service', 'failures', 'insufficientData')) {
        $v3Value = $report.$section | ConvertTo-Json -Depth 8 -Compress
        $v5Value = $v5Report.$section | ConvertTo-Json -Depth 8 -Compress
        if ($v5Value -ne $v3Value) {
            throw "Payload v5 changed base analysis section '$section'."
        }
    }
    if ($v5Report.status -ne $report.status) { throw 'Payload v5 changed the base analysis status.' }
    if ($v5ReportText.Contains($reportMarker) -or
        $v5ReportText.Contains('REPORT-PRIVATE-SUGGESTION-MUST-NOT-APPEAR') -or
        $v5ReportText.Contains('privateAiInterpretation')
    ) {
        throw 'Aggregate v5 report leaked optional private report fields.'
    }

    $categoryMarker = 'CUSTOM-CATEGORY-MUST-NOT-APPEAR'
    $payloadV6 = $payload | ConvertFrom-Json
    $payloadV6.formatVersion = 6
    $payloadV6 | Add-Member -NotePropertyName includedCategories -NotePropertyValue @('base', 'app_categories')
    $payloadV6 | Add-Member -NotePropertyName appCategories -NotePropertyValue @([ordered]@{
        packageName = 'example.target'
        automatic = '工具'
        manual = $categoryMarker
        effective = $categoryMarker
    })
    $v6InputPath = Join-Path $temporaryRoot 'synthetic-v6.pausecn.json'
    $v6OutputPath = Join-Path $temporaryRoot 'report-v6.json'
    New-TestEnvelope -Payload ($payloadV6 | ConvertTo-Json -Depth 8 -Compress) -Password $password |
        Set-Content -LiteralPath $v6InputPath -Encoding utf8

    & $analyzer `
        -InputPath $v6InputPath `
        -OutputPath $v6OutputPath `
        -Passphrase $securePassword `
        -MinTriggerSuccessRatePercent 95 `
        -Force | Out-Null

    $v6ReportText = Get-Content -Raw -LiteralPath $v6OutputPath
    $v6Report = $v6ReportText | ConvertFrom-Json
    foreach ($section in @('criteria', 'intervention', 'service', 'failures', 'insufficientData')) {
        $v3Value = $report.$section | ConvertTo-Json -Depth 8 -Compress
        $v6Value = $v6Report.$section | ConvertTo-Json -Depth 8 -Compress
        if ($v6Value -ne $v3Value) {
            throw "Payload v6 changed base analysis section '$section'."
        }
    }
    if ($v6Report.status -ne $report.status) { throw 'Payload v6 changed the base analysis status.' }
    if ($v6ReportText.Contains($categoryMarker) -or
        $v6ReportText.Contains('appCategories') -or
        $v6ReportText.Contains('app_categories')
    ) {
        throw 'Aggregate v6 report leaked optional application categories.'
    }

    $failedReportPath = Join-Path $temporaryRoot 'failed-report.json'
    & $analyzer `
        -InputPath $inputPath `
        -OutputPath $failedReportPath `
        -Passphrase $securePassword `
        -MinTriggerSuccessRatePercent 97 `
        -Force | Out-Null
    $failedReport = Get-Content -Raw -LiteralPath $failedReportPath | ConvertFrom-Json
    if ($failedReport.status -ne 'FAIL' -or @($failedReport.failures).Count -eq 0) {
        throw 'Analyzer did not fail a breached trigger-success threshold.'
    }

    $insufficientReportPath = Join-Path $temporaryRoot 'insufficient-report.json'
    & $analyzer `
        -InputPath $inputPath `
        -OutputPath $insufficientReportPath `
        -Passphrase $securePassword `
        -MinInterventionSamples 30 `
        -MinTriggerSuccessRatePercent 95 `
        -Force | Out-Null
    $insufficientReport = Get-Content -Raw -LiteralPath $insufficientReportPath | ConvertFrom-Json
    if ($insufficientReport.status -ne 'INSUFFICIENT_DATA' -or
        @($insufficientReport.insufficientData).Count -eq 0
    ) {
        throw 'Analyzer did not report insufficient sample evidence.'
    }

    $wrongPasswordRejected = $false
    try {
        & $analyzer `
            -InputPath $inputPath `
            -OutputPath (Join-Path $temporaryRoot 'wrong-password.json') `
            -Passphrase (ConvertTo-SecureString 'wrong-password' -AsPlainText -Force) `
            -Force | Out-Null
    } catch {
        $wrongPasswordRejected = $_.Exception.Message -match 'incorrect|modified|密码|修改'
    }
    if (-not $wrongPasswordRejected) {
        throw 'Analyzer did not reject an incorrect password.'
    }

    $sourceOverwriteRejected = $false
    try {
        & $analyzer `
            -InputPath $inputPath `
            -OutputPath $inputPath `
            -Passphrase $securePassword `
            -Force | Out-Null
    } catch {
        $sourceOverwriteRejected = $_.Exception.Message -match 'must not overwrite'
    }
    if (-not $sourceOverwriteRejected) {
        throw 'Analyzer allowed its source export to be overwritten.'
    }

    Write-Output 'Field export analyzer self-test passed.'
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
        $systemTemporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolvedTemporaryRoot.StartsWith($systemTemporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove unexpected path: $resolvedTemporaryRoot"
        }
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}
