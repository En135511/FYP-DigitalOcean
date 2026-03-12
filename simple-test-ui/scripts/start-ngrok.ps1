[CmdletBinding()]
param(
    [string]$NgrokAuthToken = "",
    [string]$NgrokDomain = "",
    [int]$BackendPort = 8080,
    [int]$LocalTunnelPort = 8081,
    [switch]$InstallCaddy,
    [switch]$InstallNgrok
)

$ErrorActionPreference = "Stop"

function Get-NgrokMajorVersion {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -Path $Path)) {
        return 0
    }
    try {
        $v = (& $Path version 2>$null | Out-String).Trim()
        if ($v -match '(?i)ngrok version\s+(\d+)') {
            return [int]$matches[1]
        }
    } catch {
        return 0
    }
    return 0
}

function Test-UsableExecutablePath {
    param(
        [string]$Path,
        [string]$CommandName
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }
    if (-not (Test-Path -Path $Path)) {
        return $false
    }

    if ($CommandName -eq "ngrok") {
        $major = Get-NgrokMajorVersion -Path $Path
        if ($major -lt 3) {
            return $false
        }
    }

    return $true
}

function Install-NgrokDirect {
    param([Parameter(Mandatory = $true)][string]$ToolsRoot)

    $installDir = Join-Path $ToolsRoot "ngrok"
    $exePath = Join-Path $installDir "ngrok.exe"
    if (Test-Path -Path $exePath) {
        return $exePath
    }

    New-Item -ItemType Directory -Path $installDir -Force | Out-Null

    $downloadPlans = @(
        @{
            Name = "ngrok v3 stable zip"
            Url = "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-windows-amd64.zip"
        }
    )

    $errors = @()
    foreach ($plan in $downloadPlans) {
        $zipPath = Join-Path $env:TEMP ("ngrok_" + [guid]::NewGuid().ToString("N") + ".zip")
        $extractDir = Join-Path $env:TEMP ("ngrok_extract_" + [guid]::NewGuid().ToString("N"))
        try {
            Write-Host "Downloading ngrok via $($plan.Name)..."
            Invoke-WebRequest -Uri $plan.Url -OutFile $zipPath -UseBasicParsing -Headers @{ "User-Agent" = "BrailleAI-setup" } -TimeoutSec 120

            New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
            Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

            $downloadedExe = Get-ChildItem -Path $extractDir -Recurse -Filter "ngrok.exe" -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if (-not $downloadedExe) {
                throw "Downloaded archive did not contain ngrok.exe."
            }

            Copy-Item -Path $downloadedExe.FullName -Destination $exePath -Force
            if (-not (Test-Path -Path $exePath)) {
                throw "ngrok.exe was not written to $exePath"
            }

            $major = Get-NgrokMajorVersion -Path $exePath
            if ($major -lt 3) {
                throw "Downloaded ngrok binary is not v3 (major=$major)."
            }

            Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
            Remove-Item -Path $extractDir -Recurse -Force -ErrorAction SilentlyContinue
            return $exePath
        } catch {
            $errors += "$($plan.Name): $($_.Exception.Message)"
            Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
            Remove-Item -Path $extractDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    $joined = $errors -join " | "
    throw "Unable to install ngrok automatically. Tried direct sources. Errors: $joined"
}

function Resolve-CommandPath {
    param(
        [Parameter(Mandatory = $true)][string]$CommandName,
        [string]$WingetId = "",
        [switch]$Install,
        [string]$ToolsRoot = ""
    )

    if ($CommandName -eq "ngrok" -and -not [string]::IsNullOrWhiteSpace($ToolsRoot)) {
        $localNgrok = Join-Path $ToolsRoot "ngrok\ngrok.exe"
        if (Test-UsableExecutablePath -Path $localNgrok -CommandName "ngrok") {
            return $localNgrok
        }
    }

    $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and (Test-UsableExecutablePath -Path $cmd.Source -CommandName $CommandName)) {
        return $cmd.Source
    }

    $wingetPackages = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
    if (Test-Path -Path $wingetPackages) {
        $byPackageName = Get-ChildItem -Path $wingetPackages -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*$CommandName*" }
        foreach ($packageDir in $byPackageName) {
            $candidateExe = Join-Path $packageDir.FullName "$CommandName.exe"
            if (Test-UsableExecutablePath -Path $candidateExe -CommandName $CommandName) {
                return $candidateExe
            }
        }
    }

    if ($Install -and $WingetId) {
        try {
            Write-Host "Installing $CommandName via winget..."
            winget install -e --id $WingetId --accept-package-agreements --accept-source-agreements | Out-Host
            $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($cmd -and (Test-UsableExecutablePath -Path $cmd.Source -CommandName $CommandName)) {
                return $cmd.Source
            }
        } catch {
            Write-Warning "winget install failed for $CommandName. Falling back to direct install when available."
        }
    }

    if ($CommandName -eq "ngrok") {
        if ([string]::IsNullOrWhiteSpace($ToolsRoot)) {
            throw "ToolsRoot is required for direct ngrok install fallback."
        }
        try {
            if ($Install) {
                Write-Host "ngrok not found after winget check. Installing local ngrok fallback..."
            } else {
                Write-Host "ngrok not found. Installing local ngrok fallback..."
            }
            return Install-NgrokDirect -ToolsRoot $ToolsRoot
        } catch {
            throw "ngrok is not installed and automatic fallback failed. Install manually or retry with internet access. Details: $($_.Exception.Message)"
        }
    }

    throw "$CommandName is not installed."
}

function Wait-ForNgrokUrl {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSec = 35
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $payload = Invoke-RestMethod -Uri "http://127.0.0.1:4040/api/tunnels" -TimeoutSec 3
            $targetAddr = "http://127.0.0.1:$Port"
            $match = $payload.tunnels |
                Where-Object { $_.proto -eq "https" -and $_.config.addr -eq $targetAddr } |
                Select-Object -First 1
            if (-not $match) {
                $match = $payload.tunnels |
                    Where-Object { $_.proto -eq "https" } |
                    Select-Object -First 1
            }
            if ($match -and $match.public_url) {
                return [string]$match.public_url
            }
        } catch {
            # Wait and retry while ngrok boots.
        }
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function Test-ApiHealth {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 8,
        [hashtable]$Headers = @{}
    )

    try {
        $result = Invoke-RestMethod -Uri $Url -TimeoutSec $TimeoutSec -Headers $Headers
        return [string]$result
    } catch {
        return $null
    }
}

function Get-StoredNgrokToken {
    param(
        [string]$TokenFilePath,
        [string]$ConfigPath,
        [string]$GlobalConfigPath = ""
    )

    if (-not [string]::IsNullOrWhiteSpace($TokenFilePath) -and (Test-Path -Path $TokenFilePath)) {
        $raw = (Get-Content -Path $TokenFilePath -Raw -ErrorAction SilentlyContinue).Trim()
        if (-not [string]::IsNullOrWhiteSpace($raw)) {
            return $raw
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ConfigPath) -and (Test-Path -Path $ConfigPath)) {
        $content = Get-Content -Path $ConfigPath -Raw -ErrorAction SilentlyContinue
        if ($content -match '(?im)^\s*authtoken\s*:\s*("?)([^"\r\n]+)\1\s*$') {
            return $matches[2].Trim()
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($GlobalConfigPath) -and (Test-Path -Path $GlobalConfigPath)) {
        $content = Get-Content -Path $GlobalConfigPath -Raw -ErrorAction SilentlyContinue
        if ($content -match '(?im)^\s*authtoken\s*:\s*("?)([^"\r\n]+)\1\s*$') {
            return $matches[2].Trim()
        }
    }

    return ""
}

function Write-ProjectNgrokConfig {
    param(
        [Parameter(Mandatory = $true)][string]$ConfigPath,
        [Parameter(Mandatory = $true)][string]$AuthToken,
        [Parameter(Mandatory = $true)][int]$TunnelPort,
        [string]$Domain = ""
    )

    $configDir = Split-Path -Parent $ConfigPath
    New-Item -ItemType Directory -Path $configDir -Force | Out-Null

    $configContent = @(
        "version: 2",
        "authtoken: $AuthToken",
        "tunnels:",
        "  brailleai:",
        "    proto: http",
        "    addr: http://127.0.0.1:$TunnelPort"
    )
    if (-not [string]::IsNullOrWhiteSpace($Domain)) {
        $configContent += "    domain: $Domain"
    }
    Set-Content -Path $ConfigPath -Value ($configContent -join "`n") -Encoding UTF8
}

function Read-LogTail {
    param(
        [string]$Path,
        [int]$Lines = 8
    )
    if (-not (Test-Path -Path $Path)) {
        return ""
    }
    return (Get-Content -Path $Path -Tail $Lines | Out-String).Trim()
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$uiRoot = Split-Path -Parent $scriptDir
$deployDir = Join-Path $uiRoot "deploy"
$toolsDir = Join-Path $deployDir "tools"
$publishScript = Join-Path $scriptDir "publish-braillai.ps1"
$ngrokStdout = Join-Path $deployDir "ngrok.stdout.log"
$ngrokStderr = Join-Path $deployDir "ngrok.stderr.log"
$ngrokTokenFile = Join-Path $deployDir "ngrok.authtoken"
$projectNgrokConfig = Join-Path $deployDir "ngrok.yml"
$userNgrokConfig = Join-Path $env:LOCALAPPDATA "ngrok\ngrok.yml"

New-Item -ItemType Directory -Path $deployDir -Force | Out-Null
New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null

Write-Host "Preparing Caddy route for local tunnel..."
& $publishScript -BackendPort $BackendPort -LocalTunnelPort $LocalTunnelPort -InstallCaddy:$InstallCaddy

$ngrokExe = Resolve-CommandPath -CommandName "ngrok" -WingetId "Ngrok.Ngrok" -Install:$InstallNgrok -ToolsRoot $toolsDir
$ngrokMajor = Get-NgrokMajorVersion -Path $ngrokExe
if ($ngrokMajor -lt 3) {
    throw "Detected ngrok major version '$ngrokMajor'. ngrok v3 is required."
}
Write-Host "Using ngrok executable: $ngrokExe (v$ngrokMajor)"

$tokenToUse = if ($NgrokAuthToken) {
    $NgrokAuthToken
} elseif ($env:NGROK_AUTHTOKEN) {
    $env:NGROK_AUTHTOKEN
} else {
    Get-StoredNgrokToken -TokenFilePath $ngrokTokenFile -ConfigPath $projectNgrokConfig -GlobalConfigPath $userNgrokConfig
}

if ($NgrokDomain) {
    $NgrokDomain = $NgrokDomain.Trim()
    $NgrokDomain = $NgrokDomain -replace '^https?://', ''
    $NgrokDomain = $NgrokDomain.TrimEnd('/')
}

if ($tokenToUse) {
    $tokenToUse = $tokenToUse.Trim()
    Write-Host "Configuring ngrok authtoken for project-local config..."
    Write-ProjectNgrokConfig -ConfigPath $projectNgrokConfig -AuthToken $tokenToUse -TunnelPort $LocalTunnelPort -Domain $NgrokDomain
    Set-Content -Path $ngrokTokenFile -Value $tokenToUse -Encoding ASCII
} else {
    throw "ngrok authtoken is required but none was found. Run once with -NgrokAuthToken '<your_token>' to save it for future runs."
}

Get-Process ngrok -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

Write-Host "Starting ngrok tunnel..."
$ngrokConfigArg = if ($projectNgrokConfig -match '\s') { '"' + $projectNgrokConfig + '"' } else { $projectNgrokConfig }
$ngrokArgs = @("start", "--config", $ngrokConfigArg, "brailleai")

Start-Process `
    -FilePath $ngrokExe `
    -ArgumentList $ngrokArgs `
    -WorkingDirectory $uiRoot `
    -RedirectStandardOutput $ngrokStdout `
    -RedirectStandardError $ngrokStderr `
    -WindowStyle Hidden | Out-Null

$publicUrl = Wait-ForNgrokUrl -Port $LocalTunnelPort
if (-not $publicUrl) {
    $tail = Read-LogTail -Path $ngrokStderr -Lines 12
    if (-not [string]::IsNullOrWhiteSpace($tail)) {
        throw "Unable to discover the ngrok public URL. Check logs: $ngrokStderr. Latest ngrok error: $tail"
    }
    throw "Unable to discover the ngrok public URL. Check logs: $ngrokStderr"
}
if ($NgrokDomain -and ($publicUrl -notmatch [regex]::Escape($NgrokDomain))) {
    Write-Warning "Tunnel is up, but URL '$publicUrl' does not match requested domain '$NgrokDomain'. Check ngrok dashboard domain binding."
}

$localHealth = Test-ApiHealth -Url "http://127.0.0.1:$BackendPort/api/braille/health"
$tunnelHealth = Test-ApiHealth -Url "$publicUrl/api/braille/health" -Headers @{ "ngrok-skip-browser-warning" = "1" }

Write-Host ""
Write-Host "Public tunnel is ready:"
Write-Host "URL:           $publicUrl"
if ($NgrokDomain) {
    Write-Host "Requested URL: https://$NgrokDomain"
}
Write-Host "Workspace:     $publicUrl/index.html"
Write-Host "Perkins Input: $publicUrl/perkins.html"
Write-Host "Inspect API:   http://127.0.0.1:4040"
Write-Host ""
Write-Host "Health checks:"
$localHealthText = if ($localHealth) { $localHealth } else { "FAILED" }
$tunnelHealthText = if ($tunnelHealth) {
    $tunnelHealth
} else {
    "FAILED (check ngrok warning page or backend logs)"
}
Write-Host "Local API  : $localHealthText"
Write-Host "Tunnel API : $tunnelHealthText"
Write-Host ""
Write-Host "Logs:"
Write-Host "ngrok stdout: $ngrokStdout"
Write-Host "ngrok stderr: $ngrokStderr"
Write-Host ""
Write-Host "Note: ngrok free domains can show an interstitial warning page for first-time browser visits."
