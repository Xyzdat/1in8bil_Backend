param(
    [string]$BaseUrl = 'http://127.0.0.1:8081/api/v1',
    [ValidatePattern('^[A-Z]{3}$')][string]$Country = 'RUS',
    [switch]$RestartBackend
)
$ErrorActionPreference = 'Stop'
Invoke-RestMethod "$BaseUrl/guest" -Method Post -ContentType 'application/json' -Body '{}' -SessionVariable lifeBrowser | Out-Null
function Send-LifeCommand([string]$Path, $Payload, [string]$Key = [guid]::NewGuid().ToString()) {
    Invoke-RestMethod "$BaseUrl/$Path" -Method Post -WebSession $lifeBrowser -ContentType 'application/json; charset=utf-8' `
        -Headers @{'Idempotency-Key'=$Key} -Body ($Payload | ConvertTo-Json -Depth 10 -Compress)
}
$life = Send-LifeCommand 'sessions/generated/local' @{countryIso3=$Country;birthYear=2026}
$path = "sessions/$($life.id)"
$decisions = 0
$restarted = $false
while ($life.state.age -lt 7 -and $life.state.status -ne 'COMPLETE') {
    $version = $life.version
    $key = [guid]::NewGuid().ToString()
    $life = Send-LifeCommand "$path/step" @{expectedVersion=$version} $key
    $retry = Send-LifeCommand "$path/step" @{expectedVersion=$version} $key
    if ($retry.version -ne $life.version -or $retry.simulation.elapsedMonths -ne $life.simulation.elapsedMonths) {
        throw 'Repeated step changed the session.'
    }
    if ($life.state.pendingDecision) {
        if ($life.state.age -lt 3) { throw 'Decision before age 3.' }
        if ($RestartBackend -and -not $restarted) {
            docker compose restart backend | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Backend restart failed.' }
            $healthy = $false
            for ($attempt = 0; $attempt -lt 30; $attempt++) {
                try {
                    $health = Invoke-RestMethod "$BaseUrl/health" -TimeoutSec 2
                    if ($health.status -eq 'UP') { $healthy = $true; break }
                } catch { }
                Start-Sleep -Seconds 1
            }
            if (-not $healthy) { throw 'Backend did not become ready.' }
            $restored = Invoke-RestMethod "$BaseUrl/$path" -WebSession $lifeBrowser
            if ($restored.version -ne $life.version -or $restored.state.pendingDecision.id -ne $life.state.pendingDecision.id) {
                throw 'Pending decision was not restored.'
            }
            $life = $restored
            $restarted = $true
        }
        $decision = $life.state.pendingDecision
        $life = Send-LifeCommand "$path/decisions/$($decision.id)/answers" @{
            expectedVersion=$life.version;optionId=$decision.options[0].id
        }
        $decisions++
    }
}
$restored = Invoke-RestMethod "$BaseUrl/$path" -WebSession $lifeBrowser
if ($restored.simulation.elapsedMonths -ne $life.simulation.elapsedMonths -or $restored.version -ne $life.version) { throw 'Restore mismatch.' }
[pscustomobject]@{
    Id=$life.id; Country=$life.character.birthplace.country; Age=$life.state.age
    Months=$life.simulation.elapsedMonths; Events=$life.state.history.Count; Decisions=$decisions
    FamilyMembers=$life.simulation.family.Count; RestoredAfterRestart=$restarted
    Statistics=$life.simulation.statisticsRelease
    Status=$life.state.status; Aura=$life.state.stats.aura; BirthFacts=($null -ne $life.character.birthFacts)
}
