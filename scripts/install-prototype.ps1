[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SkipBuild,
    [switch]$NoLaunch,
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk"
)

$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resolvedApkPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $ApkPath))
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$defaultJdk = "C:\Program Files\Android\Android Studio\jbr"

function Resolve-AdbPath {
    $sdkRoots = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique

    foreach ($sdkRoot in $sdkRoots) {
        $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }
    throw "未找到 adb。请在 Android Studio 的 SDK Manager 中安装 Android SDK Platform-Tools。"
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & $script:adb @script:adbTarget @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') 执行失败：`n$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        throw "未找到 Gradle Wrapper：$gradleWrapper"
    }
    $javaExecutable = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME "bin\java.exe"
    } else {
        $null
    }
    if (-not $javaExecutable -or -not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        $bundledJava = Join-Path $defaultJdk "bin\java.exe"
        if (-not (Test-Path -LiteralPath $bundledJava -PathType Leaf)) {
            throw "未找到可用 JDK。请安装 Android Studio，或把 JAVA_HOME 指向 JDK 17 及以上版本。"
        }
        $env:JAVA_HOME = $defaultJdk
    }

    Write-Host "正在构建原型 APK…"
    & $gradleWrapper --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Debug APK 构建失败。"
    }
}

if (-not (Test-Path -LiteralPath $resolvedApkPath -PathType Leaf)) {
    throw "未找到 Debug APK：$resolvedApkPath"
}

$script:adb = Resolve-AdbPath
$deviceLines = @(& $script:adb devices -l | Select-Object -Skip 1 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$authorizedDevices = @($deviceLines | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
})

$selectedSerial = $Serial
if ([string]::IsNullOrWhiteSpace($selectedSerial)) {
    if ($authorizedDevices.Count -eq 0) {
        $unauthorized = @($deviceLines | Where-Object { $_ -match '\sunauthorized(?:\s|$)' })
        if ($unauthorized.Count -gt 0) {
            throw "设备尚未授权 USB 调试。请解锁手机、接受电脑的调试授权，然后重试。"
        }
        throw "未发现已连接的安卓设备。请连接手机并开启 USB 调试，或先启动 Android 模拟器。"
    }
    if ($authorizedDevices.Count -gt 1) {
        throw "发现多个设备：$($authorizedDevices -join ', ')。请使用 -Serial 指定其中一个。"
    }
    $selectedSerial = $authorizedDevices[0]
} elseif ($selectedSerial -notin $authorizedDevices) {
    throw "设备 $selectedSerial 未连接或未授权。当前可用设备：$($authorizedDevices -join ', ')"
}

$script:adbTarget = @("-s", $selectedSerial)
$deviceState = ((Invoke-Adb -Arguments @("get-state")) -join "").Trim()
if ($deviceState -ne "device") {
    throw "设备 $selectedSerial 尚未就绪：$deviceState"
}

$model = ((Invoke-Adb -Arguments @("shell", "getprop", "ro.product.model")) -join "").Trim()
$androidVersion = ((Invoke-Adb -Arguments @("shell", "getprop", "ro.build.version.release")) -join "").Trim()
$apkHash = (Get-FileHash -LiteralPath $resolvedApkPath -Algorithm SHA256).Hash

Write-Host "正在安装到 $model（Android $androidVersion，$selectedSerial）…"
$installOutput = Invoke-Adb -Arguments @("install", "-r", $resolvedApkPath)
if (-not (($installOutput -join "`n") -match '(?m)^Success\s*$')) {
    throw "安装命令没有返回 Success：`n$($installOutput -join [Environment]::NewLine)"
}

$installedPath = (Invoke-Adb -Arguments @("shell", "pm", "path", "app.pausecn")) -join "`n"
if (-not $installedPath.Contains("package:")) {
    throw "安装命令成功，但设备上未找到 app.pausecn。"
}

if (-not $NoLaunch) {
    Invoke-Adb -Arguments @(
        "shell", "am", "start",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-n", "app.pausecn/.MainActivity"
    ) | Out-Null
}

Write-Host ""
Write-Host "安装完成：停一下（当前工作区构建，版本号请查看应用设置页）"
Write-Host "设备：$model（Android $androidVersion，$selectedSerial）"
Write-Host "APK：$resolvedApkPath"
Write-Host "SHA-256：$apkHash"
if ($NoLaunch) {
    Write-Host "应用未自动启动；请从手机桌面打开“停一下”。"
} else {
    Write-Host "应用已启动。请按应用内说明，由你本人在系统设置中开启无障碍服务。"
}
