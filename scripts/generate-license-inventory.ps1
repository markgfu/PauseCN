[CmdletBinding()]
param(
    [string]$SbomPath,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($SbomPath)) {
    $SbomPath = Join-Path $repoRoot 'docs\SBOM.cdx.json'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot 'app\build\reports\security\license-inventory.json'
}

$gradleUserHome = if (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    $env:GRADLE_USER_HOME
} else {
    Join-Path $env:USERPROFILE '.gradle'
}
$artifactCache = Join-Path $gradleUserHome 'caches\modules-2\files-2.1'

function Get-PomPath {
    param(
        [string]$Group,
        [string]$Name,
        [string]$Version
    )

    $moduleDirectory = Join-Path $artifactCache (Join-Path $Group (Join-Path $Name $Version))
    if (-not (Test-Path -LiteralPath $moduleDirectory)) {
        return $null
    }
    Get-ChildItem -Recurse -File -LiteralPath $moduleDirectory -Filter "$Name-$Version.pom" |
        Select-Object -First 1 -ExpandProperty FullName
}

function Convert-LicenseToSpdx {
    param([string]$Name)

    $normalized = $Name.Trim()
    if ($normalized -match '^Apache-2\.0$' -or $normalized -match '^The Apache (Software )?License, Version 2\.0$') {
        return 'Apache-2.0'
    }
    if ($normalized -match '^BSD-3-Clause$') {
        return 'BSD-3-Clause'
    }
    if ($normalized -match '^MIT( License)?$') {
        return 'MIT'
    }
    if ($normalized -match '^Eclipse Public License(,? Version)? 2\.0$' -or $normalized -match '^EPL-2\.0$') {
        return 'EPL-2.0'
    }
    return $null
}

function Resolve-PomLicenses {
    param(
        [string]$Group,
        [string]$Name,
        [string]$Version,
        [System.Collections.Generic.HashSet[string]]$Visited
    )

    $coordinate = "$Group`:$Name`:$Version"
    if (-not $Visited.Add($coordinate)) {
        throw "POM parent cycle detected at $coordinate."
    }
    $pomPath = Get-PomPath -Group $Group -Name $Name -Version $Version
    if ([string]::IsNullOrWhiteSpace($pomPath)) {
        throw "No cached POM found for $coordinate. Resolve dependencies before generating the license inventory."
    }

    $pom = [xml](Get-Content -Raw -LiteralPath $pomPath)
    $licenses = @(
        $pom.project.licenses.license |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace([string]$_.name) -or
                -not [string]::IsNullOrWhiteSpace([string]$_.url)
            } |
            ForEach-Object {
                $declaredName = ([string]$_.name).Trim()
                $spdxId = Convert-LicenseToSpdx -Name $declaredName
                if ([string]::IsNullOrWhiteSpace($spdxId)) {
                    throw "Unmapped declared license '$declaredName' in $coordinate."
                }
                [ordered]@{
                    id = $spdxId
                    name = $declaredName
                    url = ([string]$_.url).Trim()
                    acknowledgement = 'declared'
                    declaredBy = $coordinate
                }
            }
    )
    if ($licenses.Count -gt 0) {
        return $licenses
    }

    $parent = $pom.project.parent
    if ($null -eq $parent -or [string]::IsNullOrWhiteSpace([string]$parent.groupId)) {
        throw "No declared or inherited license found for $coordinate."
    }
    Resolve-PomLicenses `
        -Group ([string]$parent.groupId).Trim() `
        -Name ([string]$parent.artifactId).Trim() `
        -Version ([string]$parent.version).Trim() `
        -Visited $Visited
}

function Get-Sha256Text {
    param([string]$Value)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $digest = [System.Security.Cryptography.SHA256]::HashData($bytes)
    [Convert]::ToHexString($digest).ToLowerInvariant()
}

$bom = Get-Content -Raw -LiteralPath (Resolve-Path -LiteralPath $SbomPath) | ConvertFrom-Json
$components = @($bom.components | Sort-Object purl)
$inventoryComponents = [System.Collections.Generic.List[object]]::new()
foreach ($component in $components) {
    $visited = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $licenses = @(Resolve-PomLicenses `
        -Group $component.group `
        -Name $component.name `
        -Version $component.version `
        -Visited $visited)
    $inventoryComponents.Add([ordered]@{
        purl = $component.purl
        licenses = $licenses
    })
}

$componentIdentity = (@($components.purl) -join "`n")
$report = [ordered]@{
    schemaVersion = 1
    source = 'resolved Maven POM license declarations'
    componentSetSha256 = Get-Sha256Text -Value $componentIdentity
    componentCount = $components.Count
    declaredLicenseCoverage = $inventoryComponents.Count
    spdxMappedCoverage = @(
        $inventoryComponents | Where-Object {
            @($_.licenses | Where-Object { -not [string]::IsNullOrWhiteSpace($_.id) }).Count -gt 0
        }
    ).Count
    components = @($inventoryComponents)
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
Write-Output "License inventory complete: components=$($components.Count) declared=$($report.declaredLicenseCoverage) spdx=$($report.spdxMappedCoverage) output=$OutputPath"
