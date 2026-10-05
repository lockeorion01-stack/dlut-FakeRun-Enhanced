param(
    [Parameter(Mandatory = $true)]
    [string]$SigningConfig
)

$ErrorActionPreference = 'Stop'
$releaseConfig = Get-Content -LiteralPath $SigningConfig -Raw -Encoding UTF8 | ConvertFrom-Json
$releaseVariables = @('ANDROID_KEYSTORE_PATH', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')
$previousValues = @{}
foreach ($name in $releaseVariables) {
    if ([string]::IsNullOrWhiteSpace($releaseConfig.$name)) {
        throw "Signing configuration is missing $name"
    }
    $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    foreach ($name in $releaseVariables) {
        [Environment]::SetEnvironmentVariable($name, $releaseConfig.$name, 'Process')
    }
    & .\gradlew.bat :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Release build failed with exit code $LASTEXITCODE" }
} finally {
    foreach ($name in $releaseVariables) {
        [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process')
    }
    Pop-Location
}
