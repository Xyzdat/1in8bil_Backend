param(
    [ValidateSet('family-housing','work-income')][string]$Stage = 'family-housing',
    [string]$Destination = 'data/statistics/expanded/socio',
    [switch]$Refresh
)
$ErrorActionPreference = 'Stop'
$sources = Get-Content (Join-Path $PSScriptRoot 'socioeconomic-sources.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force $Destination | Out-Null
foreach ($source in $sources | Where-Object { $_.stage -eq $Stage }) {
    $path = Join-Path $Destination $source.file
    if ((Test-Path -LiteralPath $path) -and -not $Refresh) {
        Write-Output "Cached: $($source.file)"
        continue
    }
    # A failed transfer must never replace a previously complete download.
    $partial = $path + '.partial'
    Invoke-WebRequest -Uri $source.url -OutFile $partial -TimeoutSec 180
    if ((Get-Item -LiteralPath $partial).Length -eq 0) { throw "Empty download: $($source.file)" }
    Move-Item -LiteralPath $partial -Destination $path -Force
    Write-Output "Downloaded: $($source.file)"
}
