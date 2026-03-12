[CmdletBinding()]
param(
    [int]$BackendPort = 8080,
    [int]$VisionPort = 8000,
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

function Test-ArtifactInstalled {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactId
    )

    $artifactDir = Join-Path $env:USERPROFILE ".m2\repository\com\engine\$ArtifactId\0.0.1-SNAPSHOT"
    if (-not (Test-Path -Path $artifactDir)) {
        return $false
    }

    $jarPath = Join-Path $artifactDir "$ArtifactId-0.0.1-SNAPSHOT.jar"
    $pomPath = Join-Path $artifactDir "$ArtifactId-0.0.1-SNAPSHOT.pom"
    return (Test-Path -Path $jarPath) -or (Test-Path -Path $pomPath)
}

function Invoke-MavenInstall {
    param(
        [Parameter(Mandatory = $true)][string]$MvnwPath,
        [Parameter(Mandatory = $true)][string]$ModulePom,
        [Parameter(Mandatory = $true)][string]$ModuleName
    )

    Write-Host "Installing $ModuleName..."
    & $MvnwPath -f $ModulePom -DskipTests install
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install module $ModuleName"
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$uiRoot = Split-Path -Parent $scriptDir
$defaultRepoRoot = Split-Path -Parent $uiRoot

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = $defaultRepoRoot
}

$mvnw = Join-Path $RepoRoot "mvnw.cmd"
$appPom = Join-Path $RepoRoot "brailleai-application\pom.xml"
$louisCli = Join-Path $RepoRoot "liblouis\bin\lou_translate.exe"
$louisTable = Join-Path $RepoRoot "liblouis\tables\en-us-g2.ctb"

if (-not (Test-Path -Path $mvnw)) {
    throw "mvnw.cmd not found at $mvnw"
}
if (-not (Test-Path -Path $appPom)) {
    throw "Spring Boot module pom not found at $appPom"
}
if (-not (Test-Path -Path $louisCli)) {
    throw "Liblouis CLI not found at $louisCli"
}
if (-not (Test-Path -Path $louisTable)) {
    throw "Liblouis table not found at $louisTable"
}

$requiredArtifacts = @(
    "brailleai-api",
    "brailleai-core",
    "brailleai-liblouis",
    "brailleai-output",
    "brailleai-vision",
    "brailleai-web"
)

$missingArtifacts = $requiredArtifacts | Where-Object { -not (Test-ArtifactInstalled -ArtifactId $_) }

if ($missingArtifacts.Count -gt 0) {
    Write-Host "Missing local Maven artifacts detected: $($missingArtifacts -join ', ')"
    # Dependency-safe order:
    # output -> api -> core/liblouis -> vision -> web -> application
    $installOrder = @(
        "brailleai-output",
        "brailleai-api",
        "brailleai-core",
        "brailleai-liblouis",
        "brailleai-vision",
        "brailleai-web",
        "brailleai-application"
    )

    foreach ($module in $installOrder) {
        $modulePom = Join-Path $RepoRoot "$module\pom.xml"
        if (-not (Test-Path -Path $modulePom)) {
            throw "Module pom not found: $modulePom"
        }
        Invoke-MavenInstall -MvnwPath $mvnw -ModulePom $modulePom -ModuleName $module
    }
} else {
    Write-Host "Required local Maven artifacts already installed."
}

$jvmArgs = "-Dserver.port=$BackendPort " +
           "-Dvision.service.base-url=http://127.0.0.1:$VisionPort " +
           "-DLOUIS_CLI_PATH=..\liblouis\bin\lou_translate.exe " +
           "-DLOUIS_TABLE=..\liblouis\tables\en-us-g2.ctb"

Write-Host "Starting Spring Boot backend on port $BackendPort..."
& $mvnw -f $appPom -DskipTests spring-boot:run "-Dspring-boot.run.jvmArguments=$jvmArgs"
if ($LASTEXITCODE -ne 0) {
    throw "Spring Boot backend exited with code $LASTEXITCODE"
}
