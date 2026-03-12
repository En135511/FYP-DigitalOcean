[CmdletBinding()]
param(
    [int]$BackendPort = 8080,
    [int]$LocalTunnelPort = 8081,
    [switch]$InstallCaddy
)

$ErrorActionPreference = "Stop"

function Install-CaddyDirect {
    param([Parameter(Mandatory = $true)][string]$ToolsRoot)

    $installDir = Join-Path $ToolsRoot "caddy"
    $exePath = Join-Path $installDir "caddy.exe"
    if (Test-Path -Path $exePath) {
        return $exePath
    }

    New-Item -ItemType Directory -Path $installDir -Force | Out-Null

    function Get-LatestGithubWindowsZip {
        try {
            $release = Invoke-RestMethod `
                -Uri "https://api.github.com/repos/caddyserver/caddy/releases/latest" `
                -Headers @{ "User-Agent" = "BrailleAI-setup" } `
                -TimeoutSec 20
            $asset = $release.assets |
                Where-Object { $_.name -match '^caddy_.*_windows_amd64\.zip$' } |
                Select-Object -First 1
            if ($asset -and $asset.browser_download_url) {
                return [string]$asset.browser_download_url
            }
        } catch {
            # Fall through to next candidate.
        }
        return $null
    }

    $githubZip = Get-LatestGithubWindowsZip
    $downloadPlans = @(
        @{
            Name = "Caddy API binary"
            Url = "https://caddyserver.com/api/download?os=windows&arch=amd64"
            IsZip = $false
            TempFile = Join-Path $env:TEMP ("caddy_" + [guid]::NewGuid().ToString("N") + ".exe")
        }
    )
    if (-not [string]::IsNullOrWhiteSpace($githubZip)) {
        $downloadPlans += @{
            Name = "GitHub release zip"
            Url = $githubZip
            IsZip = $true
            TempFile = Join-Path $env:TEMP ("caddy_" + [guid]::NewGuid().ToString("N") + ".zip")
        }
    }

    $errors = @()
    foreach ($plan in $downloadPlans) {
        $extractDir = Join-Path $env:TEMP ("caddy_extract_" + [guid]::NewGuid().ToString("N"))
        try {
            Write-Host "Downloading caddy via $($plan.Name)..."
            Invoke-WebRequest -Uri $plan.Url -OutFile $plan.TempFile -UseBasicParsing -Headers @{ "User-Agent" = "BrailleAI-setup" } -TimeoutSec 120

            if ($plan.IsZip) {
                New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
                Expand-Archive -Path $plan.TempFile -DestinationPath $extractDir -Force
                $downloadedExe = Get-ChildItem -Path $extractDir -Recurse -Filter "caddy.exe" -ErrorAction SilentlyContinue |
                    Select-Object -First 1
                if (-not $downloadedExe) {
                    throw "Downloaded archive did not contain caddy.exe."
                }
                Copy-Item -Path $downloadedExe.FullName -Destination $exePath -Force
            } else {
                Copy-Item -Path $plan.TempFile -Destination $exePath -Force
            }

            if (-not (Test-Path -Path $exePath)) {
                throw "caddy.exe was not written to $exePath"
            }
            $exeInfo = Get-Item -Path $exePath
            if ($exeInfo.Length -lt 4MB) {
                throw "Downloaded caddy.exe appears too small ($($exeInfo.Length) bytes)."
            }

            Remove-Item -Path $plan.TempFile -Force -ErrorAction SilentlyContinue
            Remove-Item -Path $extractDir -Recurse -Force -ErrorAction SilentlyContinue
            return $exePath
        } catch {
            $errors += "$($plan.Name): $($_.Exception.Message)"
            Remove-Item -Path $plan.TempFile -Force -ErrorAction SilentlyContinue
            Remove-Item -Path $extractDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    $joined = $errors -join " | "
    throw "Unable to install caddy automatically. Tried direct sources. Errors: $joined"
}

function Resolve-CommandPath {
    param(
        [Parameter(Mandatory = $true)][string]$CommandName,
        [string]$WingetId = "",
        [switch]$Install,
        [string]$ToolsRoot = ""
)

    if ($CommandName -eq "caddy" -and -not [string]::IsNullOrWhiteSpace($ToolsRoot)) {
        $localCaddy = Join-Path $ToolsRoot "caddy\caddy.exe"
        if (Test-Path -Path $localCaddy) {
            return $localCaddy
        }
    }

    $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) {
        return $cmd.Source
    }

    $wingetPackages = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
    if (Test-Path -Path $wingetPackages) {
        $byPackageName = Get-ChildItem -Path $wingetPackages -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*$CommandName*" }
        foreach ($packageDir in $byPackageName) {
            $candidateExe = Join-Path $packageDir.FullName "$CommandName.exe"
            if (Test-Path -Path $candidateExe) {
                return $candidateExe
            }
        }
    }

    if ($Install -and $WingetId) {
        try {
            Write-Host "Installing $CommandName via winget..."
            winget install -e --id $WingetId --accept-package-agreements --accept-source-agreements | Out-Host
            $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($cmd) {
                return $cmd.Source
            }
        } catch {
            Write-Warning "winget install failed for $CommandName. Falling back to direct install when available."
        }
    }

    if ($CommandName -eq "caddy") {
        if ([string]::IsNullOrWhiteSpace($ToolsRoot)) {
            throw "ToolsRoot is required for direct caddy install fallback."
        }
        try {
            if ($Install) {
                Write-Host "Caddy not found after winget check. Installing local caddy fallback..."
            } else {
                Write-Host "Caddy not found. Installing local caddy fallback..."
            }
            return Install-CaddyDirect -ToolsRoot $ToolsRoot
        } catch {
            throw "Caddy is not installed and automatic fallback failed. Install manually or retry with internet access. Details: $($_.Exception.Message)"
        }
    }

    throw "$CommandName is not installed."
}

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 15
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return $true
            }
        } catch {
            # Keep waiting for startup.
        }
        Start-Sleep -Milliseconds 400
    }
    return $false
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$uiRoot = Split-Path -Parent $scriptDir
$deployDir = Join-Path $uiRoot "deploy"
$toolsDir = Join-Path $deployDir "tools"
$configPath = Join-Path $deployDir "Caddyfile"
$caddyStdout = Join-Path $deployDir "caddy.stdout.log"
$caddyStderr = Join-Path $deployDir "caddy.stderr.log"

New-Item -ItemType Directory -Path $deployDir -Force | Out-Null
New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null

$caddyExe = Resolve-CommandPath -CommandName "caddy" -WingetId "CaddyServer.Caddy" -Install:$InstallCaddy -ToolsRoot $toolsDir

$caddyConfig = @"
{
    admin 127.0.0.1:2019
    auto_https off
}

:$LocalTunnelPort {
    encode zstd gzip
    root * "."

    @api path /api/*
    handle @api {
        request_header -Origin
        reverse_proxy 127.0.0.1:$BackendPort
    }

    file_server
}
"@

Set-Content -Path $configPath -Value $caddyConfig -Encoding UTF8
$validateCmd = "`"$caddyExe`" validate --config `"$configPath`" --adapter caddyfile >nul 2>nul"
cmd.exe /c $validateCmd | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Caddy config validation failed for $configPath"
}

Get-Process caddy -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Process `
    -FilePath $caddyExe `
    -ArgumentList "run --config `"$configPath`" --adapter caddyfile" `
    -WorkingDirectory $uiRoot `
    -RedirectStandardOutput $caddyStdout `
    -RedirectStandardError $caddyStderr `
    -WindowStyle Hidden | Out-Null

if (-not (Wait-HttpReady -Url "http://127.0.0.1:$LocalTunnelPort/" -TimeoutSec 16)) {
    throw "Caddy route did not become ready on port $LocalTunnelPort. Check logs: $caddyStderr"
}

Write-Host "Caddy route is live."
Write-Host "UI:  http://127.0.0.1:$LocalTunnelPort/"
Write-Host "API: /api/* -> http://127.0.0.1:$BackendPort"
Write-Host "Config: $configPath"
