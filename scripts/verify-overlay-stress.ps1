[CmdletBinding()]
param(
    [ValidateRange(1, 10000)]
    [int]$Iterations = 200,
    [string]$Serial,
    [string]$TargetPackage = "com.android.chrome",
    [string]$NeutralPackage = "com.google.android.contacts",
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk",
    [string]$ReportPath = "build/reports/overlay-stress.json",
    [switch]$SkipInstall,
    [switch]$ForceStopTarget,
    [ValidateRange(500, 30000)]
    [int]$OverlayTimeoutMs = 30000,
    [ValidateRange(0, 5000)]
    [int]$NeutralSettleMs = 500,
    [ValidateRange(1, 200)]
    [int]$WindowAuditInterval = 20,
    [ValidateRange(500, 30000)]
    [int]$DismissTimeoutMs = 30000
)

$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resolvedApkPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $ApkPath))
$resolvedReportPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $ReportPath))
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
        [int]$TimeoutMs,
        [int]$PollMilliseconds = 50,
        [string]$FailureMessage = "Timed out waiting for device state."
    )

    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.ElapsedMilliseconds -lt $TimeoutMs) {
        if (& $Condition) {
            return [int]$timer.ElapsedMilliseconds
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }
    throw $FailureMessage
}

function Get-DebugProbeXml {
    return (Invoke-Adb -Arguments @(
        "shell", "run-as", "app.pausecn", "cat", "shared_prefs/debug_probe.xml"
    ) -AllowFailure) -join "`n"
}

function Test-OverlayWindowPresent {
    $windows = (Invoke-Adb -Arguments @("shell", "dumpsys", "window", "windows")) -join "`n"
    return $windows.Contains("停一下")
}

function Get-ProbeLogText {
    if (-not (Test-Path -LiteralPath $script:probeLogPath)) {
        return ""
    }
    try {
        return [string](Get-Content -LiteralPath $script:probeLogPath -Raw -ErrorAction Stop)
    } catch {
        return ""
    }
}

function Wait-ProbeLog {
    param(
        [Parameter(Mandatory = $true)][string]$ExpectedText,
        [Parameter(Mandatory = $true)][int]$TimeoutMs,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    Wait-Until -TimeoutMs $TimeoutMs -PollMilliseconds 50 -FailureMessage $FailureMessage -Condition {
        ([string](Get-ProbeLogText)).Contains($ExpectedText)
    } | Out-Null
}

function Invoke-StressControl {
    param(
        [Parameter(Mandatory = $true)][string]$Action,
        [Parameter(Mandatory = $true)][int]$Iteration
    )

    Invoke-Adb -Arguments @(
        "shell", "am", "broadcast",
        "-a", $Action,
        "-n", "app.pausecn/app.pausecn.debug.DebugProbeReceiver",
        "--ei", "stress_iteration", $Iteration.ToString()
    ) | Out-Null
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

    $script:probeXml = ""
    Wait-Until -TimeoutMs 30000 -PollMilliseconds 100 `
        -FailureMessage "Debug probe did not complete action $Action." -Condition {
            $script:probeXml = Get-DebugProbeXml
            return $script:probeXml.Contains("name=`"request_id`">$requestId</string>")
        } | Out-Null
    if (-not $script:probeXml.Contains("<boolean name=`"success`" value=`"true`"")) {
        throw "Debug probe action $Action failed:`n$script:probeXml"
    }
    return $script:probeXml
}

function Get-Percentile {
    param(
        [AllowEmptyCollection()]
        [long[]]$Values = @(),
        [Parameter(Mandatory = $true)]
        [double]$Percentile
    )

    if ($Values.Count -eq 0) {
        return $null
    }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    return [long]$sorted[$index]
}

function Test-ServiceBound {
    $services = (Invoke-Adb -Arguments @(
        "shell", "dumpsys", "activity", "services", "app.pausecn"
    )) -join "`n"
    return $services.Contains("app.pausecn/.accessibility.PauseAccessibilityService")
}

function Save-Report {
    param([Parameter(Mandatory = $true)][hashtable]$Report)

    $reportDirectory = Split-Path -Parent $resolvedReportPath
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $json = $Report | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($resolvedReportPath, $json + [Environment]::NewLine)
}

$deviceState = (Invoke-Adb -Arguments @("get-state")) -join ""
if ($deviceState.Trim() -ne "device") {
    throw "Selected adb target is not ready: $deviceState"
}

$packageWasInstalled = ((Invoke-Adb -Arguments @(
    "shell", "pm", "path", "app.pausecn"
) -AllowFailure) -join "").Contains("package:")
$originalServices = ((Invoke-Adb -Arguments @(
    "shell", "settings", "--user", "0", "get", "secure", "enabled_accessibility_services"
)) -join "").Trim()
$originalAccessibilityEnabled = ((Invoke-Adb -Arguments @(
    "shell", "settings", "--user", "0", "get", "secure", "accessibility_enabled"
)) -join "").Trim()
$originalWindowAnimationScale = ((Invoke-Adb -Arguments @(
    "shell", "settings", "get", "global", "window_animation_scale"
)) -join "").Trim()
$originalTransitionAnimationScale = ((Invoke-Adb -Arguments @(
    "shell", "settings", "get", "global", "transition_animation_scale"
)) -join "").Trim()
$originalAnimatorDurationScale = ((Invoke-Adb -Arguments @(
    "shell", "settings", "get", "global", "animator_duration_scale"
)) -join "").Trim()

$startedAt = [DateTimeOffset]::UtcNow
$launchLatencies = [System.Collections.Generic.List[long]]::new()
$neutralTransitionLatencies = [System.Collections.Generic.List[long]]::new()
$dismissLatencies = [System.Collections.Generic.List[long]]::new()
$completedIterations = 0
$historyCount = $null
$overlayShowLogCount = 0
$stressDismissLogCount = 0
$failureSignatures = @()
$serviceBoundAfterRun = $false
$failure = $null
$evidenceCollected = $false
$windowAuditsCompleted = 0
$finalOverlayWindowPresent = $null
$sdk = ((Invoke-Adb -Arguments @("shell", "getprop", "ro.build.version.sdk")) -join "").Trim()
$buildFingerprint = ((Invoke-Adb -Arguments @(
    "shell", "getprop", "ro.build.fingerprint"
)) -join "").Trim()
$fontScale = ((Invoke-Adb -Arguments @(
    "shell", "settings", "get", "system", "font_scale"
)) -join "").Trim()
$displaySize = ((Invoke-Adb -Arguments @("shell", "wm", "size")) -join "`n").Trim()
$displayDensity = ((Invoke-Adb -Arguments @("shell", "wm", "density")) -join "`n").Trim()
$apkSha256 = (Get-FileHash -LiteralPath $resolvedApkPath -Algorithm SHA256).Hash
$installedApkSha256 = $null
$probeLogPath = Join-Path ([System.IO.Path]::GetTempPath()) "pausecn-probe-$([Guid]::NewGuid()).log"
$probeLogErrorPath = "$probeLogPath.err"
$probeLogProcess = $null

function Collect-RunEvidence {
    $snapshot = Invoke-Probe -Action "app.pausecn.debug.SNAPSHOT_SAFETY_SCENARIO"
    if ($snapshot -notmatch '<boolean name="preview_completed" value="true"') {
        throw "Debug scenario did not complete the onboarding preview gate:`n$snapshot"
    }
    $historyMatch = [regex]::Match($snapshot, '<int name="history_count" value="(\d+)"')
    if (-not $historyMatch.Success) {
        throw "Snapshot did not contain history_count:`n$snapshot"
    }
    $script:historyCount = [int]$historyMatch.Groups[1].Value

    $probeLog = Get-ProbeLogText
    $script:overlayShowLogCount = [regex]::Matches(
        $probeLog,
        "overlay shown package=$([regex]::Escape($TargetPackage))"
    ).Count
    $script:stressDismissLogCount = [regex]::Matches(
        $probeLog,
        "dismiss overlay for stress probe"
    ).Count

    $runtimeLog = (Invoke-Adb -Arguments @(
        "logcat", "-b", "main", "-b", "system", "-b", "crash", "-d"
    )) -join "`n"
    $failurePatterns = @(
        "Process: app\.pausecn",
        "ANR in app\.pausecn",
        "app\.pausecn.*WindowLeaked",
        "WindowLeaked.*app\.pausecn",
        "has leaked window.*app\.pausecn",
        "Process app\.pausecn .* died"
    )
    $script:failureSignatures = @($failurePatterns | Where-Object {
        [regex]::IsMatch($runtimeLog, $_, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    })
    $script:serviceBoundAfterRun = Test-ServiceBound
    $script:evidenceCollected = $true
}

try {
    if ($SkipInstall) {
        if (-not $packageWasInstalled) {
            throw "-SkipInstall was requested but app.pausecn is not installed."
        }
    } else {
        Invoke-Adb -Arguments @("install", "-r", $resolvedApkPath) | Out-Null
    }
    $installedPathOutput = (Invoke-Adb -Arguments @(
        "shell", "pm", "path", "app.pausecn"
    )) -join "`n"
    $installedBasePath = @($installedPathOutput -split "`n" | Where-Object {
        $_ -match '^package:.*base\.apk$'
    } | Select-Object -First 1)
    if ($installedBasePath.Count -ne 1) {
        throw "Could not resolve the installed app.pausecn base APK path."
    }
    $installedBasePath = $installedBasePath[0].Substring("package:".Length).Trim()
    $deviceHashOutput = (Invoke-Adb -Arguments @(
        "shell", "sha256sum", $installedBasePath
    )) -join " "
    if ($deviceHashOutput -notmatch '^([0-9a-fA-F]{64})\s') {
        throw "Could not hash the installed app.pausecn APK: $deviceHashOutput"
    }
    $installedApkSha256 = $Matches[1].ToUpperInvariant()
    if ($installedApkSha256 -ne $apkSha256) {
        throw "Installed app.pausecn APK hash does not match $resolvedApkPath."
    }
    $targetPath = (Invoke-Adb -Arguments @(
        "shell", "pm", "path", $TargetPackage
    ) -AllowFailure) -join ""
    if (-not $targetPath.Contains("package:")) {
        throw "Target package $TargetPackage is not installed on the selected device."
    }
    if ($NeutralPackage -eq $TargetPackage) {
        throw "Neutral package must differ from target package $TargetPackage."
    }
    $neutralPath = (Invoke-Adb -Arguments @(
        "shell", "pm", "path", $NeutralPackage
    ) -AllowFailure) -join ""
    if (-not $neutralPath.Contains("package:")) {
        throw "Neutral package $NeutralPackage is not installed on the selected device."
    }

    # This is a window-lifecycle test, not an animation benchmark. Disabling cosmetic
    # animations prevents the emulator compositor from distorting a long stability run.
    Invoke-Adb -Arguments @("shell", "settings", "put", "global", "window_animation_scale", "0") | Out-Null
    Invoke-Adb -Arguments @("shell", "settings", "put", "global", "transition_animation_scale", "0") | Out-Null
    Invoke-Adb -Arguments @("shell", "settings", "put", "global", "animator_duration_scale", "0") | Out-Null

    Invoke-Probe -Action "app.pausecn.debug.SEED_SAFETY_SCENARIO" -Extras @(
        "--es", "target_package", $TargetPackage,
        "--es", "target_label", "Stress probe target"
    ) | Out-Null

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
    Wait-Until -TimeoutMs 45000 -PollMilliseconds 100 `
        -FailureMessage "PauseAccessibilityService did not bind." -Condition {
            Test-ServiceBound
        } | Out-Null

    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    $probeLogProcess = Start-Process `
        -FilePath $adb `
        -ArgumentList @(
            "-s", $selectedSerial, "logcat", "-v", "raw",
            "-s", "PauseSafetyProbe:D", "*:S"
        ) `
        -WindowStyle Hidden `
        -RedirectStandardOutput $probeLogPath `
        -RedirectStandardError $probeLogErrorPath `
        -PassThru
    Start-Sleep -Milliseconds 250
    if ($probeLogProcess.HasExited) {
        $probeError = if (Test-Path -LiteralPath $probeLogErrorPath) {
            Get-Content -LiteralPath $probeLogErrorPath -Raw
        } else { "" }
        throw "Persistent Debug log observer exited before the stress run: $probeError"
    }
    if (Test-OverlayWindowPresent) {
        throw "Stress run started with a stale real overlay window."
    }

    for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
        Invoke-StressControl -Action "app.pausecn.debug.PREPARE_STRESS_ITERATION" -Iteration $iteration

        if ($ForceStopTarget) {
            Invoke-Adb -Arguments @("shell", "am", "force-stop", $TargetPackage) | Out-Null
        }
        $launchTimer = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-Adb -Arguments @(
            "shell", "monkey", "-p", $TargetPackage,
            "-c", "android.intent.category.LAUNCHER", "1"
        ) | Out-Null
        Wait-ProbeLog `
            -ExpectedText "overlay shown package=$TargetPackage iteration=$iteration" `
            -TimeoutMs $OverlayTimeoutMs `
            -FailureMessage "Iteration $iteration did not log an overlay show within $OverlayTimeoutMs ms."
        $launchTimer.Stop()
        $launchLatencies.Add($launchTimer.ElapsedMilliseconds)

        $dismissTimer = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-StressControl -Action "app.pausecn.debug.DISMISS_STRESS_OVERLAY" -Iteration $iteration
        Wait-ProbeLog `
            -ExpectedText "dismiss overlay for stress probe iteration=$iteration" `
            -TimeoutMs $DismissTimeoutMs `
            -FailureMessage "Iteration $iteration did not log Debug stress dismissal within $DismissTimeoutMs ms."
        $dismissTimer.Stop()
        $dismissLatencies.Add($dismissTimer.ElapsedMilliseconds)

        $neutralTransitionTimer = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-Adb -Arguments @(
            "shell", "monkey", "-p", $NeutralPackage,
            "-c", "android.intent.category.LAUNCHER", "1"
        ) | Out-Null
        $neutralTransitionTimer.Stop()
        $neutralTransitionLatencies.Add($neutralTransitionTimer.ElapsedMilliseconds)
        if ($NeutralSettleMs -gt 0) {
            Start-Sleep -Milliseconds $NeutralSettleMs
        }
        $completedIterations = $iteration

        if (($iteration % $WindowAuditInterval) -eq 0 -or $iteration -eq $Iterations) {
            $windowAuditsCompleted++
            if (Test-OverlayWindowPresent) {
                throw "Iteration $iteration left a real overlay window after Debug dismissal."
            }
        }

        if (($iteration % 5) -eq 0 -or $iteration -eq $Iterations) {
            Write-Host "Overlay stress: $iteration / $Iterations complete"
        }
    }

    Collect-RunEvidence
    $finalOverlayWindowPresent = Test-OverlayWindowPresent

    if ($historyCount -ne 0) {
        throw "Stress lifecycle transitions persisted $historyCount intervention records."
    }
    if ($overlayShowLogCount -ne $Iterations) {
        throw "Expected $Iterations overlay-show logs but found $overlayShowLogCount."
    }
    if ($stressDismissLogCount -ne $Iterations) {
        throw "Expected $Iterations stress-dismiss logs but found $stressDismissLogCount."
    }
    if ($failureSignatures.Count -ne 0) {
        throw "Runtime failure signatures detected: $($failureSignatures -join ', ')"
    }
    if (-not $serviceBoundAfterRun) {
        throw "PauseAccessibilityService was no longer bound after the stress run."
    }
    if ($finalOverlayWindowPresent) {
        throw "A real overlay window remained after the stress run."
    }
}
catch {
    $failure = $_
    if (-not $evidenceCollected) {
        try {
            Collect-RunEvidence
        } catch {
            Write-Warning "Could not collect complete failure evidence: $($_.Exception.Message)"
        }
    }
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

    $animationSettings = @(
        @{ Name = "window_animation_scale"; Value = $originalWindowAnimationScale },
        @{ Name = "transition_animation_scale"; Value = $originalTransitionAnimationScale },
        @{ Name = "animator_duration_scale"; Value = $originalAnimatorDurationScale }
    )
    foreach ($animationSetting in $animationSettings) {
        if ([string]::IsNullOrWhiteSpace($animationSetting.Value) -or $animationSetting.Value -eq "null") {
            Invoke-Adb -Arguments @(
                "shell", "settings", "delete", "global", $animationSetting.Name
            ) -AllowFailure | Out-Null
        } else {
            Invoke-Adb -Arguments @(
                "shell", "settings", "put", "global", $animationSetting.Name, $animationSetting.Value
            ) -AllowFailure | Out-Null
        }
    }

    if (-not $packageWasInstalled) {
        Invoke-Adb -Arguments @("uninstall", "app.pausecn") -AllowFailure | Out-Null
    }

    if ($null -ne $probeLogProcess -and -not $probeLogProcess.HasExited) {
        Stop-Process -Id $probeLogProcess.Id -Force -ErrorAction SilentlyContinue
        $probeLogProcess.WaitForExit(5000) | Out-Null
    }
}

$probeLogSha256 = if (Test-Path -LiteralPath $probeLogPath) {
    (Get-FileHash -LiteralPath $probeLogPath -Algorithm SHA256).Hash
} else { $null }
$probeLogBytes = if (Test-Path -LiteralPath $probeLogPath) {
    (Get-Item -LiteralPath $probeLogPath).Length
} else { 0 }

$report = @{
    schemaVersion = 1
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    startedAtUtc = $startedAt.ToString("O")
    device = @{
        serial = $selectedSerial
        apiLevel = $sdk
        buildFingerprint = $buildFingerprint
        fontScale = $fontScale
        displaySize = $displaySize
        displayDensity = $displayDensity
    }
    scenario = @{
        targetPackage = $TargetPackage
        debugApkSha256 = $apkSha256
        installedDebugApkSha256 = $installedApkSha256
        installedApkDuringRun = (-not $SkipInstall)
        requestedIterations = $Iterations
        completedIterations = $completedIterations
        forceStopsTargetBeforeEachLaunch = [bool]$ForceStopTarget
        dismissalSurface = "Debug-only internal broadcast"
        neutralSurfaceBetweenLaunches = $NeutralPackage
        neutralSettleMilliseconds = $NeutralSettleMs
        animationsDisabledDuringRun = $true
        realWindowAuditInterval = $WindowAuditInterval
    }
    latencyMilliseconds = @{
        measurement = "Host-observed timing using one persistent Debug log stream: target request to overlay; Debug probe request to removal; neutral-app transition. Emulator baseline, not a device UX SLA."
        launchP50 = Get-Percentile -Values $launchLatencies.ToArray() -Percentile 0.50
        launchP95 = Get-Percentile -Values $launchLatencies.ToArray() -Percentile 0.95
        launchMax = if ($launchLatencies.Count) { ($launchLatencies | Measure-Object -Maximum).Maximum } else { $null }
        neutralTransitionP50 = Get-Percentile -Values $neutralTransitionLatencies.ToArray() -Percentile 0.50
        neutralTransitionP95 = Get-Percentile -Values $neutralTransitionLatencies.ToArray() -Percentile 0.95
        neutralTransitionMax = if ($neutralTransitionLatencies.Count) { ($neutralTransitionLatencies | Measure-Object -Maximum).Maximum } else { $null }
        dismissP50 = Get-Percentile -Values $dismissLatencies.ToArray() -Percentile 0.50
        dismissP95 = Get-Percentile -Values $dismissLatencies.ToArray() -Percentile 0.95
        dismissMax = if ($dismissLatencies.Count) { ($dismissLatencies | Measure-Object -Maximum).Maximum } else { $null }
        launchSamples = $launchLatencies.ToArray()
        neutralTransitionSamples = $neutralTransitionLatencies.ToArray()
        dismissSamples = $dismissLatencies.ToArray()
    }
    evidence = @{
        overlayShowLogCount = $overlayShowLogCount
        stressDismissLogCount = $stressDismissLogCount
        historyCount = $historyCount
        serviceBoundAfterRun = $serviceBoundAfterRun
        realWindowAuditsCompleted = $windowAuditsCompleted
        finalOverlayWindowPresent = $finalOverlayWindowPresent
        runtimeFailureSignatures = $failureSignatures
        persistentProbeLogSha256 = $probeLogSha256
        persistentProbeLogBytes = $probeLogBytes
    }
    passed = ($null -eq $failure)
    failure = if ($failure) { $failure.Exception.Message } else { $null }
}

Save-Report -Report $report
$report | ConvertTo-Json -Depth 6
Remove-Item -LiteralPath $probeLogPath, $probeLogErrorPath -Force -ErrorAction SilentlyContinue

if ($failure) {
    throw "Overlay stress failed. See $resolvedReportPath. $($failure.Exception.Message)"
}
