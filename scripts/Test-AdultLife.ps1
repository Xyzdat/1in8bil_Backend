param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$Country = 'JPN',
    [int]$TargetAge = 35
)

$ErrorActionPreference = 'Stop'
if ($TargetAge -lt 19 -or $TargetAge -gt 130) { throw 'TargetAge must be between 19 and 130.' }
# A separate guest cookie: this script never opens or edits the player's existing save.
$web = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/guest" -WebSession $web -ContentType 'application/json' -Body '{}' | Out-Null
$life = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/sessions/generated/local" -WebSession $web -ContentType 'application/json' `
    -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() } -Body (@{ countryIso3 = $Country } | ConvertTo-Json)
$path = "$BaseUrl/api/v1/sessions/$($life.id)"
$answers = 0
$verifiedAdult = $false

while ($life.state.status -eq 'ACTIVE' -and ($life.state.age -lt $TargetAge -or $null -ne $life.state.pendingDecision)) {
    $body = @{ expectedVersion = $life.version }
    $route = "$path/step"
    if ($null -ne $life.state.pendingDecision) {
        $body.optionId = $life.state.pendingDecision.options[0].id
        $route = "$path/decisions/$($life.state.pendingDecision.id)/answers"
        $answers++
    }
    $key = [guid]::NewGuid().ToString()
    $payload = $body | ConvertTo-Json -Compress
    $life = Invoke-RestMethod -Method Post -Uri $route -WebSession $web -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = $key } -Body $payload
    if (!$verifiedAdult -and $null -ne $life.simulation.adult) {
        $duplicate = Invoke-RestMethod -Method Post -Uri $route -WebSession $web -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = $key } -Body $payload
        $restored = Invoke-RestMethod -Uri $path -WebSession $web
        if ($duplicate.version -ne $life.version -or $restored.version -ne $life.version -or
            $restored.simulation.elapsedMonths -ne $life.simulation.elapsedMonths -or
            $restored.state.history.Count -ne $life.state.history.Count -or
            $restored.simulation.adult.modelVersion -ne 'adult-1') { throw 'Adult save or idempotency check failed.' }
        $verifiedAdult = $true
    }
}
$finances = Invoke-RestMethod -Uri "$path/finances" -WebSession $web
if ($finances.version -ne $life.version) { throw 'Finance version does not match the session.' }
$output = Join-Path $PSScriptRoot '../target/adult-http-smoke.json'
@{ session = $life; finances = $finances } | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $output -Encoding utf8
[pscustomobject]@{
    SessionId = $life.id
    Age = $life.state.age
    Status = $life.state.status
    AdultVerified = $verifiedAdult
    Events = $life.state.history.Count
    Answers = $answers
    Stories = $life.simulation.adult.stories.Count
    AvailableRub = $finances.summary.availableBalance
    DebtRub = $finances.summary.debt
    NetWorthRub = $finances.summary.netWorth
    Occupation = $finances.summary.occupation
}
if (!$verifiedAdult -and $life.state.status -ne 'COMPLETE') { throw 'Adult state was not activated.' }
