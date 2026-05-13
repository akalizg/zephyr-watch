$ErrorActionPreference = "Stop"

function Invoke-JsonGet {
    param(
        [Parameter(Mandatory = $true)][string]$Uri
    )
    return Invoke-RestMethod -Uri $Uri -Method Get
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][object]$Body
    )
    return Invoke-RestMethod -Uri $Uri -Method Post -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 6)
}

function Assert-ApiHealth {
    $health = Invoke-JsonGet -Uri "http://localhost:8080/actuator/health"
    if ($health.status -ne "UP") {
        throw "Spring Boot API is not healthy."
    }
}

function Get-LatestOpenAlert {
    $response = Invoke-JsonGet -Uri "http://localhost:8080/api/alerts?status=OPEN&limit=1"
    $items = @($response.data)
    if ($items.Count -eq 0) {
        throw "No OPEN alert found. Run the online inference flow first so alert_event has data."
    }
    return $items[0]
}

function Get-FeedbackSampleCount {
    $response = Invoke-JsonGet -Uri "http://localhost:8080/api/learning/feedback-samples?limit=500"
    return @($response.data).Count
}

Assert-ApiHealth

$beforeCount = Get-FeedbackSampleCount
$alert = Get-LatestOpenAlert
$alertId = if ($alert.alertId) { $alert.alertId } else { $alert.alert_id }
$machineId = if ($alert.machineId) { $alert.machineId } else { $alert.machine_id }
$riskLevel = if ($alert.riskLevel) { $alert.riskLevel } else { $alert.risk_level }

$reviewBody = @{
    alertId = $alertId
    reviewer = "codex-review-check"
    reviewLabel = "TRUE_POSITIVE"
    reviewComment = "verification flow"
}

Write-Host "Reviewing alert: $alertId machine=$machineId riskLevel=$riskLevel"
$reviewResult = Invoke-JsonPost -Uri "http://localhost:8080/api/alerts/review" -Body $reviewBody
Write-Host "Review API status: $($reviewResult.status)"

Start-Sleep -Seconds 8

$reviewLabels = Invoke-JsonGet -Uri "http://localhost:8080/api/learning/review-labels?limit=20"
$feedbackSamples = Invoke-JsonGet -Uri "http://localhost:8080/api/learning/feedback-samples?limit=20"
$afterCount = @($feedbackSamples.data).Count

$matchedReview = @($reviewLabels.data) | Where-Object {
    $candidateAlertId = if ($_.alertId) { $_.alertId } else { $_.alert_id }
    $candidateAlertId -eq $alertId
} | Select-Object -First 1
$matchedSample = @($feedbackSamples.data) | Where-Object { $_.alertId -eq $alertId } | Select-Object -First 1

if (-not $matchedReview) {
    throw "Review label was not exported through /api/learning/review-labels."
}

if (-not $matchedSample) {
    throw "feedback_training_sample was not generated for alert $alertId. Check AlertReviewJob and online_feature_snapshot."
}

Write-Host "Before feedback sample count: $beforeCount"
Write-Host "After  feedback sample count: $afterCount"
Write-Host "Matched review label: $($matchedReview.reviewLabel)"
Write-Host "Matched sample machineId=$($matchedSample.machineId) windowEnd=$($matchedSample.windowEnd)"
Write-Host "Review feedback flow verification passed."
