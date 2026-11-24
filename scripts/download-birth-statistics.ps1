param([string]$Destination = 'data/statistics/wdi-candidate')
$ErrorActionPreference = 'Stop'
# Downloads a candidate release only. Published game releases are not overwritten.
if (Test-Path -LiteralPath $Destination) { throw 'Choose a new destination to preserve immutable releases.' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$sourceUrl = 'https://api.worldbank.org/v2/country/RUS;JPN;IND/indicator/SP.POP.TOTL;SP.DYN.CBRT.IN;SP.POP.BRTH.MF;SP.URB.TOTL.IN.ZS?source=2&date=2023&format=json&per_page=100'
$rawPath = Join-Path $Destination 'observations.json'
Invoke-WebRequest -Uri $sourceUrl -OutFile $rawPath
$payload = Get-Content -LiteralPath $rawPath -Raw | ConvertFrom-Json
if ($payload[0].pages -ne 1 -or $payload[1].Count -ne 12 -or @($payload[1] | Where-Object {$null -eq $_.value}).Count -gt 0) {
    throw 'Incomplete World Bank response; candidate is not usable.'
}
$manifest = @{
    id = 'wdi-2023-' + $payload[0].lastupdated.Replace('-', '')
    sourceUrl = $sourceUrl
    sourceUpdated = $payload[0].lastupdated
    retrievedAt = [DateTime]::UtcNow.ToString('o')
    license = 'CC-BY-4.0'
    sha256 = (Get-FileHash -LiteralPath $rawPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Destination 'manifest.json') -Encoding utf8
Write-Output "Downloaded candidate $($manifest.id), 12 observations. Review before publishing a new engine release."
