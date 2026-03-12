[CmdletBinding()]
param(
    [string]$NgrokAuthToken = "",
    [string]$NgrokDomain = "",
    [int]$BackendPort = 8080,
    [int]$VisionPort = 8000,
    [int]$LocalTunnelPort = 8081,
    [string]$BackendStartCommand = "",
    [string]$BackendWorkDir = "",
    [string]$VisionStartCommand = "",
    [string]$VisionWorkDir = "",
    [switch]$InstallCaddy,
    [switch]$InstallNgrok,
    [int]$StartupTimeoutSec = 300
)

$ErrorActionPreference = "Stop"

function Test-PortOpen {
    param([int]$Port)
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne(350)
        if ($connected -and $client.Connected) {
            $client.EndConnect($async) | Out-Null
            $client.Close()
            return $true
        }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Wait-PortOpen {
    param(
        [int]$Port,
        [int]$TimeoutSec
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen -Port $Port) {
            return $true
        }
        Start-Sleep -Milliseconds 600
    }
    return $false
}

function Start-CommandInBackground {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string]$WorkDir,
        [Parameter(Mandatory = $true)][string]$StdOutPath,
        [Parameter(Mandatory = $true)][string]$StdErrPath
    )

    if (-not (Test-Path -Path $WorkDir)) {
        throw "$Name working directory does not exist: $WorkDir"
    }

    Write-Host "Starting $Name..."
    $proc = Start-Process `
        -FilePath "cmd.exe" `
        -ArgumentList @("/c", $Command) `
        -WorkingDirectory $WorkDir `
        -RedirectStandardOutput $StdOutPath `
        -RedirectStandardError $StdErrPath `
        -WindowStyle Hidden `
        -PassThru

    return $proc
}

function Wait-PortOpenOrProcessExit {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][int]$TimeoutSec,
        [Parameter(Mandatory = $true)][int]$ProcessId
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen -Port $Port) {
            return $true
        }
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            return $false
        }
        Start-Sleep -Milliseconds 600
    }
    return $false
}

function Try-ResolveDefaultBackend {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][int]$BackendPort,
        [Parameter(Mandatory = $true)][int]$VisionPort
    )

    $backendStarter = Join-Path $RepoRoot "simple-test-ui\scripts\start-backend.ps1"
    if (Test-Path -Path $backendStarter) {
        $command = "powershell -ExecutionPolicy Bypass -File "".\simple-test-ui\scripts\start-backend.ps1"" -BackendPort $BackendPort -VisionPort $VisionPort"
        return @{
            WorkDir = $RepoRoot
            Command = $command
        }
    }

    return $null
}

function Resolve-PythonLauncher {
    param(
        [Parameter(Mandatory = $true)][string]$VisionRoot
    )

    $venvCandidates = @(
        (Join-Path $VisionRoot ".venv\Scripts\python.exe"),
        (Join-Path $VisionRoot "venv\Scripts\python.exe")
    ) | Select-Object -Unique

    foreach ($candidate in $venvCandidates) {
        if (Test-Path -Path $candidate) {
            return "`"$candidate`""
        }
    }

    function Test-UsableInterpreterPath {
        param([string]$Path)
        if ([string]::IsNullOrWhiteSpace($Path)) {
            return $false
        }
        if (-not (Test-Path -Path $Path)) {
            return $false
        }
        if ($Path -match "WindowsApps\\python(3)?\.exe$") {
            return $false
        }
        return $true
    }

    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand -and (Test-UsableInterpreterPath -Path $pythonCommand.Source)) {
        return "`"$($pythonCommand.Source)`""
    }

    $pyCommand = Get-Command py -ErrorAction SilentlyContinue
    if ($pyCommand -and (Test-UsableInterpreterPath -Path $pyCommand.Source)) {
        return "`"$($pyCommand.Source)`" -3"
    }

    $python3Command = Get-Command python3 -ErrorAction SilentlyContinue
    if ($python3Command -and (Test-UsableInterpreterPath -Path $python3Command.Source)) {
        return "`"$($python3Command.Source)`""
    }

    return $null
}

function Try-ResolveDefaultVisionService {
    param([int]$Port)

    $candidates = @(
        "C:\dev\brailleai-vision",
        (Join-Path $env:USERPROFILE "dev\brailleai-vision")
    ) | Select-Object -Unique

    $visionSourceFound = $false
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -Path $candidate)) {
            continue
        }
        $entrypoint = Join-Path $candidate "app\main.py"
        if (-not (Test-Path -Path $entrypoint)) {
            continue
        }
        $visionSourceFound = $true

        $pythonLauncher = Resolve-PythonLauncher -VisionRoot $candidate
        if (-not $pythonLauncher) {
            continue
        }
        Write-Host "Using Python launcher: $pythonLauncher"

        return @{
            WorkDir = $candidate
            Command = "$pythonLauncher -m uvicorn app.main:app --host 127.0.0.1 --port $Port"
        }
    }

    if ($visionSourceFound) {
        throw "Vision source was found, but no Python runtime was detected. Install Python 3, disable the Microsoft Store python alias, or create a .venv in the vision project."
    }

    return $null
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$uiRoot = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $uiRoot
$deployDir = Join-Path $uiRoot "deploy"
$ngrokScript = Join-Path $scriptDir "start-ngrok.ps1"

New-Item -ItemType Directory -Path $deployDir -Force | Out-Null

if ([string]::IsNullOrWhiteSpace($BackendWorkDir)) {
    $BackendWorkDir = $repoRoot
}

if (-not (Test-PortOpen -Port $BackendPort)) {
    if ([string]::IsNullOrWhiteSpace($BackendStartCommand)) {
        $autoBackend = Try-ResolveDefaultBackend -RepoRoot $repoRoot -BackendPort $BackendPort -VisionPort $VisionPort
        if ($autoBackend) {
            $BackendStartCommand = $autoBackend.Command
            $BackendWorkDir = $autoBackend.WorkDir
            Write-Host "Backend not detected on port $BackendPort. Auto-starting from $BackendWorkDir."
        } else {
            throw "Backend is not listening on port $BackendPort. Start Spring Boot first, or pass -BackendStartCommand."
        }
    }
    $backendProcess = Start-CommandInBackground `
        -Name "Spring Boot backend" `
        -Command $BackendStartCommand `
        -WorkDir $BackendWorkDir `
        -StdOutPath (Join-Path $deployDir "backend.stdout.log") `
        -StdErrPath (Join-Path $deployDir "backend.stderr.log")

    if (-not (Wait-PortOpenOrProcessExit -Port $BackendPort -TimeoutSec $StartupTimeoutSec -ProcessId $backendProcess.Id)) {
        throw "Backend did not become reachable on port $BackendPort. Check deploy/backend.stderr.log."
    }
    Write-Host "Backend is reachable on port $BackendPort."
} else {
    Write-Host "Backend already running on port $BackendPort."
}

if (-not (Test-PortOpen -Port $VisionPort)) {
    if ([string]::IsNullOrWhiteSpace($VisionStartCommand)) {
        $autoVision = Try-ResolveDefaultVisionService -Port $VisionPort
        if ($autoVision) {
            $VisionStartCommand = $autoVision.Command
            $VisionWorkDir = $autoVision.WorkDir
            Write-Host "Vision service not detected on port $VisionPort. Auto-starting from $VisionWorkDir."
        } else {
            throw "Vision service is not listening on port $VisionPort. Start your Python service first, or pass -VisionStartCommand."
        }
    }
    if ([string]::IsNullOrWhiteSpace($VisionWorkDir)) {
        $VisionWorkDir = $repoRoot
    }
    $visionProcess = Start-CommandInBackground `
        -Name "Python vision service" `
        -Command $VisionStartCommand `
        -WorkDir $VisionWorkDir `
        -StdOutPath (Join-Path $deployDir "vision.stdout.log") `
        -StdErrPath (Join-Path $deployDir "vision.stderr.log")

    if (-not (Wait-PortOpenOrProcessExit -Port $VisionPort -TimeoutSec $StartupTimeoutSec -ProcessId $visionProcess.Id)) {
        $visionErrPath = Join-Path $deployDir "vision.stderr.log"
        $latestVisionError = ""
        if (Test-Path -Path $visionErrPath) {
            $latestVisionError = (Get-Content -Path $visionErrPath -Tail 3 | Out-String).Trim()
        }
        if (-not [string]::IsNullOrWhiteSpace($latestVisionError)) {
            throw "Vision service did not become reachable on port $VisionPort. Check deploy/vision.stderr.log. Latest error: $latestVisionError"
        }
        throw "Vision service did not become reachable on port $VisionPort. Check deploy/vision.stderr.log."
    }
    Write-Host "Vision service is reachable on port $VisionPort."
} else {
    Write-Host "Vision service already running on port $VisionPort."
}

& $ngrokScript `
    -NgrokAuthToken $NgrokAuthToken `
    -NgrokDomain $NgrokDomain `
    -BackendPort $BackendPort `
    -LocalTunnelPort $LocalTunnelPort `
    -InstallCaddy:$InstallCaddy `
    -InstallNgrok:$InstallNgrok
