$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Output = Join-Path $PSScriptRoot "output"
$Tool = Join-Path $RepoRoot "src\ParanoidSourceDeobfuscator.java"
$Main = Join-Path $PSScriptRoot "fixtures\main"
$Support = Join-Path $PSScriptRoot "fixtures\support"

Remove-Item $Output -Recurse -Force -ErrorAction SilentlyContinue

& java $Tool $Main $Output $Support
if ($LASTEXITCODE -ne 0) {
    throw "The deobfuscator exited with code $LASTEXITCODE."
}

$Calls = Join-Path $Output "sources\example\Calls.java"
$Content = Get-Content $Calls -Raw

if (-not $Content.Contains('return "direct-ok";')) {
    throw "The direct-call fixture was not patched."
}
if (-not $Content.Contains('return "wrapper-ok";')) {
    throw "The wrapper-call fixture was not patched."
}
if ($Content -match 'getString\(\s*[+-]?[0-9]+[lL]') {
    throw "A literal getString call remains in the patched fixture."
}

Write-Host "Smoke test passed."
