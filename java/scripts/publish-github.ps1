param(
  [Parameter(Mandatory = $false)]
  [string]$Owner = $env:GITHUB_REPOSITORY_OWNER,

  [Parameter(Mandatory = $false)]
  [string]$Repo = $(if ($env:GITHUB_REPOSITORY) { $env:GITHUB_REPOSITORY.Split('/')[1] } else { $null }),

  [string]$Version = "0.1.0-SNAPSHOT",
  [string]$Group = "io.metaray",

  [string]$User = $env:GITHUB_ACTOR,
  [string]$Token = $env:GITHUB_TOKEN
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Owner)) {
  throw "Missing owner. Pass -Owner or set GITHUB_REPOSITORY_OWNER."
}
if ([string]::IsNullOrWhiteSpace($Repo)) {
  throw "Missing repo. Pass -Repo or set GITHUB_REPOSITORY (owner/repo)."
}

$Owner = $Owner.ToLowerInvariant()

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path $gradle)) {
  throw "Could not find gradlew.bat at $gradle"
}

if (-not [string]::IsNullOrWhiteSpace($User)) {
  $env:GITHUB_ACTOR = $User
}
if (-not [string]::IsNullOrWhiteSpace($Token)) {
  $env:GITHUB_TOKEN = $Token
}

Write-Host "Publishing to GitHub Packages..."
Write-Host "  Owner:   $Owner"
Write-Host "  Repo:    $Repo"
Write-Host "  Group:   $Group"
Write-Host "  Version: $Version"

& $gradle `
  :core:publishMavenPublicationToGitHubPackagesRepository `
  :jvm:publishMavenPublicationToGitHubPackagesRepository `
  :android:publishReleasePublicationToGitHubPackagesRepository `
  "-PPUBLISH_GROUP=$Group" `
  "-PPUBLISH_VERSION=$Version" `
  "-Pgpr.owner=$Owner" `
  "-Pgpr.repo=$Repo"

if ($LASTEXITCODE -ne 0) {
  throw "GitHub Packages publish failed (exit code $LASTEXITCODE)"
}

Write-Host "Publish to GitHub Packages completed."
