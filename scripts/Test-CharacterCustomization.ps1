param([string]$BaseUrl = 'http://localhost:8081')

$ErrorActionPreference = 'Stop'
# This script creates its own guest and life; it never edits a player's existing save.
$web = New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Send-Json([string]$Method, [string]$Uri, $Body, [string]$Key) {
    $headers = @{}
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    Invoke-RestMethod -Method $Method -Uri $Uri -WebSession $web -ContentType 'application/json; charset=utf-8' -Headers $headers -Body $bytes
}
Send-Json 'Post' "$BaseUrl/api/v1/guest" @{} '' | Out-Null
$creationKey = [guid]::NewGuid().ToString()
$born = Send-Json 'Post' "$BaseUrl/api/v1/sessions/generated/local" @{ countryIso3 = 'RUS' } $creationKey
$path = "$BaseUrl/api/v1/sessions/$($born.id)"
$beforeBudget = Invoke-RestMethod -Uri "$path/finances" -WebSession $web
$mother = $born.simulation.family | Where-Object { $_.role -eq 'mother' } | Select-Object -First 1
$father = $born.simulation.family | Where-Object { $_.role -eq 'father' } | Select-Object -First 1
$payload = @{
    expectedVersion = $born.version
    changes = @{
        name = 'Проверка настройки'; sex = 'FEMALE'; birthYear = 2025
        stats = @{ health = 90; happiness = 80; intelligence = 65; relationships = 70; appearance = 60; aura = 15 }
        birthplace = @{ countryCode = 'JP'; region = 'Кансай'; city = 'Кобе'; placeType = 'URBAN' }
        family = @{
            incomeQuintile = 4; housing = 'Свой дом'; religion = 'Буддизм'; language = 'ja'
            members = @(
                @{ id = $mother.id; role = 'mother'; name = 'Анна'; ageAtBirth = 32; occupation = 'Врач'; closeness = 80; trust = 75 },
                @{ id = $father.id; role = 'father'; name = 'Алексей'; ageAtBirth = 34; occupation = 'Учитель'; closeness = 75; trust = 70 },
                @{ role = 'sister'; name = 'Мария'; ageAtBirth = 4; closeness = 65; trust = 60 }
            )
        }
    }
}
$editKey = [guid]::NewGuid().ToString()
$changed = Send-Json 'Patch' "$path/character" $payload $editKey
$retry = Send-Json 'Patch' "$path/character" $payload $editKey
$recreated = Send-Json 'Post' "$BaseUrl/api/v1/sessions/generated/local" @{ countryIso3 = 'RUS' } $creationKey
$restored = Invoke-RestMethod -Uri $path -WebSession $web
$budget = Invoke-RestMethod -Uri "$path/finances" -WebSession $web
if ($changed.id -ne $born.id -or $changed.version -ne ($born.version + 1) -or $retry.version -ne $changed.version -or
    $recreated.id -ne $born.id -or $recreated.character.birthplace.countryCode -ne 'JP' -or $restored.character.birthYear -ne 2025 -or
    $changed.character.birthFacts.currency -ne 'JPY' -or $changed.character.birthFacts.sex -ne 'FEMALE' -or
    $changed.simulation.family.Count -ne 3 -or $changed.simulation.elapsedMonths -ne 0 -or
    $budget.location.countryCode -ne 'JP' -or $budget.currency -ne 'RUB' -or $budget.summary.availableBalance -ne $beforeBudget.summary.availableBalance) {
    throw 'Birth customization, persistence, idempotency or budget verification failed.'
}
$memberIds = ($changed.simulation.family.id | Sort-Object) -join ','
if ((($retry.simulation.family.id | Sort-Object) -join ',') -ne $memberIds -or
    (($restored.simulation.family.id | Sort-Object) -join ',') -ne $memberIds) { throw 'Family identity changed after a retry or reload.' }
$stepped = Send-Json 'Post' "$path/step" @{ expectedVersion = $changed.version } ([guid]::NewGuid().ToString())
$locked = $false
try {
    Send-Json 'Patch' "$path/character" @{ expectedVersion = $stepped.version; changes = @{ birthplace = @{ city = 'Другой город' } } } ([guid]::NewGuid().ToString()) | Out-Null
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 409) { throw }
    $failure = $_.ErrorDetails.Message | ConvertFrom-Json
    $locked = $failure.code -eq 'BIRTH_CONFIGURATION_LOCKED'
}
if (!$locked) { throw 'Birth customization was not locked after the first step.' }
$final = Invoke-RestMethod -Uri $path -WebSession $web
if ($final.version -ne $stepped.version -or $final.character.birthplace.city -ne 'Кобе') { throw 'Rejected edit changed the save.' }
$output = Join-Path $PSScriptRoot '../target/customization-http-smoke.json'
@{ edited = $changed; continued = $final; finances = $budget; lockedAfterStep = $locked } | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $output -Encoding utf8
[pscustomobject]@{
    SessionId = $born.id; Country = $final.character.birthplace.country; BirthYear = $final.character.birthYear
    FamilyMembers = $final.simulation.family.Count; Version = $final.version; Month = $final.simulation.elapsedMonths
    RetryVerified = $true; BirthLockedAfterStep = $locked
}
