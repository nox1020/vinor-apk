# Auto-generate Digital Asset Links — thin wrapper around generate_assetlinks.py
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$py = Join-Path $PSScriptRoot "generate_assetlinks.py"
$python = $null
foreach ($c in @("python", "python3", "py")) {
    try {
        $null = & $c --version 2>$null
        if ($LASTEXITCODE -eq 0 -or $?) { $python = $c; break }
    } catch {}
}
if (-not $python) { throw "Python not found (needed for generate_assetlinks.py)" }
& $python $py
exit $LASTEXITCODE
