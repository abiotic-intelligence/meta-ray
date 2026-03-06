param(
  [string]$Version = "0.1.0-SNAPSHOT",
  [string]$Group = "io.metaray"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $gradle)) {
  throw "Could not find gradlew.bat at $gradle"
}

Write-Host "Publishing to Maven Local..."
Write-Host "  Group:   $Group"
Write-Host "  Version: $Version"

& $gradle `
  :core:publishToMavenLocal `
  :jvm:publishToMavenLocal `
  :android:publishReleasePublicationToMavenLocal `
  "-PPUBLISH_GROUP=$Group" `
  "-PPUBLISH_VERSION=$Version"

if ($LASTEXITCODE -ne 0) {
  throw "Local publish failed (exit code $LASTEXITCODE)"
}

Write-Host "Publish to Maven Local completed."
