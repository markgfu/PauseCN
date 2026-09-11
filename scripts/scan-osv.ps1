[CmdletBinding()]
param(
    [string]$SbomPath,
    [string]$OutputPath,
    [switch]$FailOnVulnerabilities
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($SbomPath)) {
    $SbomPath = Join-Path $repoRoot 'docs\SBOM.cdx.json'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot 'app\build\reports\security\osv-scan.json'
}

$resolvedSbom = (Resolve-Path -LiteralPath $SbomPath).Path
$bom = Get-Content -Raw -LiteralPath $resolvedSbom | ConvertFrom-Json
$components = @($bom.components | Where-Object { -not [string]::IsNullOrWhiteSpace($_.purl) })
if ($components.Count -eq 0) {
    throw 'The SBOM does not contain any versioned Package URLs.'
}

$queries = @(
    $components | ForEach-Object {
        [ordered]@{
            package = [ordered]@{ purl = $_.purl }
        }
    }
)

function Invoke-OsvBatch {
    param([object[]]$BatchQueries)

    $payload = @{ queries = @($BatchQueries) } | ConvertTo-Json -Depth 8 -Compress
    Invoke-RestMethod `
        -Method Post `
        -Uri 'https://api.osv.dev/v1/querybatch' `
        -ContentType 'application/json' `
        -Body $payload
}

$vulnerabilitiesByIndex = @{}
for ($index = 0; $index -lt $components.Count; $index++) {
    $vulnerabilitiesByIndex[$index] = [System.Collections.Generic.List[object]]::new()
}

$response = Invoke-OsvBatch -BatchQueries $queries
$results = @($response.results)
if ($results.Count -ne $queries.Count) {
    throw "OSV returned $($results.Count) results for $($queries.Count) queries."
}

$pending = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $results.Count; $index++) {
    foreach ($vulnerability in @($results[$index].vulns)) {
        if ($null -ne $vulnerability) {
            $vulnerabilitiesByIndex[$index].Add($vulnerability)
        }
    }
    if ($results[$index].PSObject.Properties.Name -contains 'next_page_token') {
        $pending.Add([pscustomobject]@{
            index = $index
            query = [ordered]@{
                package = [ordered]@{ purl = $components[$index].purl }
                page_token = $results[$index].next_page_token
            }
        })
    }
}

while ($pending.Count -gt 0) {
    $pageQueries = @($pending | ForEach-Object { $_.query })
    $pageResponse = Invoke-OsvBatch -BatchQueries $pageQueries
    $pageResults = @($pageResponse.results)
    if ($pageResults.Count -ne $pending.Count) {
        throw 'OSV pagination response count did not match the request.'
    }

    $nextPending = [System.Collections.Generic.List[object]]::new()
    for ($pageIndex = 0; $pageIndex -lt $pageResults.Count; $pageIndex++) {
        $originalIndex = $pending[$pageIndex].index
        foreach ($vulnerability in @($pageResults[$pageIndex].vulns)) {
            if ($null -ne $vulnerability) {
                $vulnerabilitiesByIndex[$originalIndex].Add($vulnerability)
            }
        }
        if ($pageResults[$pageIndex].PSObject.Properties.Name -contains 'next_page_token') {
            $nextPending.Add([pscustomobject]@{
                index = $originalIndex
                query = [ordered]@{
                    package = [ordered]@{ purl = $components[$originalIndex].purl }
                    page_token = $pageResults[$pageIndex].next_page_token
                }
            })
        }
    }
    $pending = $nextPending
}

$findings = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $components.Count; $index++) {
    $records = @(
        $vulnerabilitiesByIndex[$index] |
            Sort-Object id -Unique |
            ForEach-Object {
                [ordered]@{
                    id = $_.id
                    modified = $_.modified
                    url = "https://osv.dev/vulnerability/$($_.id)"
                }
            }
    )
    if ($records.Count -gt 0) {
        $findings.Add([ordered]@{
            purl = $components[$index].purl
            group = $components[$index].group
            name = $components[$index].name
            version = $components[$index].version
            vulnerabilities = $records
        })
    }
}

$recordCount = 0
foreach ($finding in $findings) {
    $recordCount += @($finding.vulnerabilities).Count
}
$report = [ordered]@{
    schemaVersion = 1
    source = 'https://api.osv.dev/v1/querybatch'
    scannedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    sbomSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedSbom).Hash.ToLowerInvariant()
    bomSerialNumber = $bom.serialNumber
    componentCount = $components.Count
    affectedComponentCount = $findings.Count
    vulnerabilityRecordCount = $recordCount
    findings = @($findings)
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM

Write-Output "OSV scan complete: components=$($components.Count) affected=$($findings.Count) records=$recordCount output=$OutputPath"
if ($FailOnVulnerabilities -and $recordCount -gt 0) {
    exit 2
}
