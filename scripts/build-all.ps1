$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "target\dist"
$staging = Join-Path $root ".build-dist"

Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $staging
New-Item -ItemType Directory -Force -Path $staging | Out-Null

Push-Location $root
try {
    mvn -q clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    Copy-Item -Force "target\BetterMOTD-*.jar" $staging

    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $dist
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    Copy-Item -Force (Join-Path $staging "*.jar") $dist
} finally {
    Pop-Location
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $staging
}

Get-ChildItem $dist -Filter "*.jar" | Select-Object -ExpandProperty Name
