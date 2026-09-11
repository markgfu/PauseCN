[CmdletBinding()]
param(
    [string]$Serial,
    [string]$TargetPackage = "com.android.chrome",
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk"
)

$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resolvedApkPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $ApkPath))
$defaultAdb = Join-Path $env:LOCALAPPDATA "Android/Sdk/platform-tools/adb.exe"
$adb = if ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME "platform-tools/adb.exe"
} else {
    $defaultAdb
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb not found at $adb. Set ANDROID_HOME or install Android platform-tools."
}
if (-not (Test-Path -LiteralPath $resolvedApkPath)) {
    throw "Debug APK not found at $resolvedApkPath. Run .\gradlew.bat assembleDebug first."
}

$selectedSerial = $Serial
if ([string]::IsNullOrWhiteSpace($selectedSerial)) {
    $authorizedDevices = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
    })
    if ($authorizedDevices.Count -ne 1) {
        throw "Expected exactly one authorized adb device, found $($authorizedDevices.Count). Pass -Serial explicitly."
    }
    $selectedSerial = $authorizedDevices[0]
}
$adbPrefix = @("-s", $selectedSerial)

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & $adb @adbPrefix @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

function Wait-Until {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Condition,
        [int]$TimeoutSeconds = 10,
        [int]$PollMilliseconds = 250,
        [string]$FailureMessage = "Timed out waiting for device state."
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (& $Condition) {
            return
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }
    throw $FailureMessage
}

function Test-OverlayVisible {
    $windows = (Invoke-Adb -Arguments @("shell", "dumpsys", "window", "windows")) -join "`n"
    return $windows.Contains("停一下")
}

function Invoke-Probe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Action,
        [string[]]$Extras = @()
    )

    $requestId = [Guid]::NewGuid().ToString()
    $broadcastArgs = @(
        "shell", "am", "broadcast",
        "-a", $Action,
        "-n", "app.pausecn/app.pausecn.debug.DebugProbeReceiver",
        "--es", "request_id", $requestId
    ) + $Extras
    Invoke-Adb -Arguments $broadcastArgs | Out-Null

    $probeXml = $null
    Wait-Until -TimeoutSeconds 8 -FailureMessage "Debug probe did not complete action $Action." -Condition {
        $script:probeXml = (Invoke-Adb -Arguments @(
            "shell", "run-as", "app.pausecn", "cat", "shared_prefs/debug_probe.xml"
        ) -AllowFailure) -join "`n"
        return $script:probeXml.Contains("name=`"request_id`">$requestId</string>")
    }
    if (-not $script:probeXml.Contains("<boolean name=`"success`" value=`"true`"")) {
        throw "Debug probe action $Action failed:`n$script:probeXml"
    }
    return $script:probeXml
}

$deviceState = (Invoke-Adb -Arguments @("get-state")) -join ""
if ($deviceState.Trim() -ne "device") {
    throw "Selected adb target is not ready: $deviceState"
}

$packageWasInstalled = ((Invoke-Adb -Arguments @("shell", "pm", "path", "app.pausecn") -AllowFailure) -join "").Contains("package:")
$originalServices = ((Invoke-Adb -Arguments @(
    "shell", "settings", "--user", "0", "get", "secure", "enabled_accessibility_services"
)) -join "").Trim()
$originalAccessibilityEnabled = ((Invoke-Adb -Arguments @(
    "shell", "settings", "--user", "0", "get", "secure", "accessibility_enabled"
)) -join "").Trim()

$result = [ordered]@{
    targetPackage = $TargetPackage
    overlayAppeared = $false
    remainedVisibleForSeconds = 0
    dismissedBySettings = $false
    historyCount = $null
    passed = $false
}

try {
    Invoke-Adb -Arguments @("install", "-r", $resolvedApkPath) | Out-Null
    $targetPath = (Invoke-Adb -Arguments @("shell", "pm", "path", $TargetPackage) -AllowFailure) -join ""
    if (-not $targetPath.Contains("package:")) {
        throw "Target package $TargetPackage is not installed on the selected device."
    }

    Invoke-Probe -Action "app.pausecn.debug.SEED_SAFETY_SCENARIO" -Extras @(
        "--es", "target_package", $TargetPackage,
        "--es", "target_label", "Safety probe target"
    ) | Out-Null

    # Force a real service transition even when this component was already enabled
    # before an in-place APK reinstall. Writing the same secure value is not enough.
    Invoke-Adb -Arguments @(
        "shell", "settings", "--user", "0", "delete", "secure", "enabled_accessibility_services"
    ) | Out-Null
    Invoke-Adb -Arguments @(
        "shell", "settings", "--user", "0", "put", "secure", "accessibility_enabled", "0"
    ) | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-Adb -Arguments @(
        "shell", "settings", "--user", "0", "put", "secure",
        "enabled_accessibility_services", "app.pausecn/.accessibility.PauseAccessibilityService"
    ) | Out-Null
    Invoke-Adb -Arguments @(
        "shell", "settings", "--user", "0", "put", "secure", "accessibility_enabled", "1"
    ) | Out-Null
    Wait-Until -TimeoutSeconds 45 -FailureMessage "PauseAccessibilityService did not bind." -Condition {
        $services = (Invoke-Adb -Arguments @(
            "shell", "dumpsys", "activity", "services", "app.pausecn"
        )) -join "`n"
        return $services.Contains("app.pausecn/.accessibility.PauseAccessibilityService")
    }

    Invoke-Adb -Arguments @("shell", "am", "force-stop", $TargetPackage) | Out-Null
    Start-Sleep -Milliseconds 500
    # Force-stopping a foreground target can surface the launcher, which correctly
    # grants the product's short safety-transition cooldown. Clear only Debug
    # session gates after that transition so the next target launch is observable.
    Invoke-Adb -Arguments @(
        "shell", "am", "broadcast",
        "-a", "app.pausecn.debug.PREPARE_STRESS_ITERATION",
        "-n", "app.pausecn/app.pausecn.debug.DebugProbeReceiver",
        "--ei", "stress_iteration", "-1"
    ) | Out-Null
    Start-Sleep -Milliseconds 250
    Invoke-Adb -Arguments @(
        "shell", "monkey", "-p", $TargetPackage,
        "-c", "android.intent.category.LAUNCHER", "1"
    ) | Out-Null
    Wait-Until -TimeoutSeconds 15 -FailureMessage "The pause overlay did not appear over $TargetPackage." -Condition {
        Test-OverlayVisible
    }
    $result.overlayAppeared = $true

    Start-Sleep -Seconds 5
    if (-not (Test-OverlayVisible)) {
        throw "The pause overlay disappeared without a safety transition."
    }
    $result.remainedVisibleForSeconds = 5

    Invoke-Adb -Arguments @("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS") | Out-Null
    Wait-Until -TimeoutSeconds 5 -FailureMessage "The pause overlay did not yield to Android Settings." -Condition {
        -not (Test-OverlayVisible)
    }
    $result.dismissedBySettings = $true

    $snapshot = Invoke-Probe -Action "app.pausecn.debug.SNAPSHOT_SAFETY_SCENARIO"
    if ($snapshot -notmatch '<boolean name="preview_completed" value="true"') {
        throw "Debug scenario did not complete the onboarding preview gate:`n$snapshot"
    }
    $historyMatch = [regex]::Match($snapshot, '<int name="history_count" value="(\d+)"')
    if (-not $historyMatch.Success) {
        throw "Snapshot did not contain history_count:`n$snapshot"
    }
    $result.historyCount = [int]$historyMatch.Groups[1].Value
    if ($result.historyCount -ne 0) {
        throw "Safety-only window events were persisted as intervention history."
    }

    $result.passed = $true
    $result | ConvertTo-Json
}
finally {
    try {
        Invoke-Probe -Action "app.pausecn.debug.RESET_SAFETY_SCENARIO" | Out-Null
    } catch {
        Write-Warning "Could not reset debug probe data during cleanup: $($_.Exception.Message)"
    }

    if ([string]::IsNullOrWhiteSpace($originalServices) -or $originalServices -eq "null") {
        Invoke-Adb -Arguments @(
            "shell", "settings", "--user", "0", "delete", "secure", "enabled_accessibility_services"
        ) -AllowFailure | Out-Null
    } else {
        Invoke-Adb -Arguments @(
            "shell", "settings", "--user", "0", "put", "secure",
            "enabled_accessibility_services", $originalServices
        ) -AllowFailure | Out-Null
    }

    if ([string]::IsNullOrWhiteSpace($originalAccessibilityEnabled) -or $originalAccessibilityEnabled -eq "null") {
        Invoke-Adb -Arguments @(
            "shell", "settings", "--user", "0", "delete", "secure", "accessibility_enabled"
        ) -AllowFailure | Out-Null
    } else {
        Invoke-Adb -Arguments @(
            "shell", "settings", "--user", "0", "put", "secure",
            "accessibility_enabled", $originalAccessibilityEnabled
        ) -AllowFailure | Out-Null
    }

    if (-not $packageWasInstalled) {
        Invoke-Adb -Arguments @("uninstall", "app.pausecn") -AllowFailure | Out-Null
    }
}
