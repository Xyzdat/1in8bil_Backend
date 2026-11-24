param([string]$Destination = 'data/statistics/expanded')
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Destination | Out-Null
$wpp = 'https://population.un.org/wpp/assets/Excel%20Files/1_Indicator%20(Standard)/CSV_FILES/'
$cldr = 'https://raw.githubusercontent.com/unicode-org/cldr-json/48.0.0/'
$sources = [ordered]@{
    'demographic.csv.gz' = $wpp + 'WPP2024_Demographic_Indicators_Medium.csv.gz'
    'male.csv.gz' = $wpp + 'WPP2024_Life_Table_Complete_Medium_Male_2024-2100.csv.gz'
    'female.csv.gz' = $wpp + 'WPP2024_Life_Table_Complete_Medium_Female_2024-2100.csv.gz'
    'gdp.zip' = 'https://api.worldbank.org/v2/en/indicator/NY.GDP.PCAP.CD?downloadformat=csv'
    'urban.zip' = 'https://api.worldbank.org/v2/en/indicator/SP.URB.TOTL.IN.ZS?downloadformat=csv'
    'territoryInfo.json' = $cldr + 'cldr-json/cldr-core/supplemental/territoryInfo.json'
    'currencyData.json' = $cldr + 'cldr-json/cldr-core/supplemental/currencyData.json'
    'UNICODE-LICENSE.txt' = $cldr + 'LICENSE'
    'religions.html' = 'https://www.pewresearch.org/feature/religious-composition-by-country-2010-2070/'
}
foreach ($source in $sources.GetEnumerator()) {
    $path = Join-Path $Destination $source.Key
    if (Test-Path -LiteralPath $path) { Write-Output "Already downloaded: $($source.Key)"; continue }
    # Rename only a completed transfer, so a failed download is never mistaken for a complete release.
    $partial = $path + '.partial'
    Invoke-WebRequest -Uri $source.Value -OutFile $partial -TimeoutSec 1800
    Move-Item -LiteralPath $partial -Destination $path
    Write-Output "Downloaded: $($source.Key)"
}
