[CmdletBinding()]
param(
    [switch]$StopBackend,
    [switch]$StopVision
)

$ErrorActionPreference = "Stop"

function Stop-ByPort {
    param([int]$Port)
    $netstatLines = netstat -ano | Select-String ":$Port"
    $processIds = @()
    foreach ($line in $netstatLines) {
        $parts = ($line.ToString() -replace "\s+", " ").Trim().Split(" ")
        if ($parts.Count -lt 5) {
            continue
        }
        if ($parts[1] -notlike "*:$Port") {
            continue
        }
        $parsedId = 0
        if ([int]::TryParse($parts[4], [ref]$parsedId)) {
            if ($parsedId -gt 0) {
                $processIds += $parsedId
            }
        }
    }
    $processIds = $processIds | Select-Object -Unique
    foreach ($processId in $processIds) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
    return $processIds.Count
}

$ngrokStopped = 0
$ngrokProcesses = Get-Process ngrok -ErrorAction SilentlyContinue
if ($ngrokProcesses) {
    $ngrokStopped = $ngrokProcesses.Count
    $ngrokProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
}

$caddyStopped = 0
$caddyProcesses = Get-Process caddy -ErrorAction SilentlyContinue
if ($caddyProcesses) {
    $caddyStopped = $caddyProcesses.Count
    $caddyProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
}

$backendStopped = 0
$visionStopped = 0

if ($StopBackend) {
    $backendStopped = Stop-ByPort -Port 8080
}
if ($StopVision) {
    $visionStopped = Stop-ByPort -Port 8000
}

Write-Host "Stopped ngrok processes: $ngrokStopped"
Write-Host "Stopped caddy processes: $caddyStopped"
if ($StopBackend) {
    Write-Host "Stopped backend listeners on :8080: $backendStopped"
}
if ($StopVision) {
    Write-Host "Stopped vision listeners on :8000: $visionStopped"
}
