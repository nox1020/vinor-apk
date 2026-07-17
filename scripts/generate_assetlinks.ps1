# Generate assetlinks.json from release keystore (Vinor)
# Usage (from apk repo root):
#   .\scripts\generate_assetlinks.ps1
# Reads key.properties (storeFile / storePassword / keyAlias) and writes:
#   well-known\assetlinks.json

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$propsPath = Join-Path $root "key.properties"
if (-not (Test-Path $propsPath)) {
    Write-Error "key.properties not found at $propsPath"
}

$lines = Get-Content $propsPath
$get = { param($k) (($lines | Where-Object { $_ -match "^$k=" }) -replace "^$k=", "").Trim() }
$storeFile = & $get "storeFile"
$storePassword = & $get "storePassword"
$keyAlias = & $get "keyAlias"
if (-not $keyAlias) { $keyAlias = "vinor_release" }

if (-not [System.IO.Path]::IsPathRooted($storeFile)) {
    $storeFile = Join-Path $root $storeFile
}
if (-not (Test-Path $storeFile)) {
    Write-Error "Keystore not found: $storeFile"
}

$out = & keytool -list -v -keystore $storeFile -alias $keyAlias -storepass $storePassword 2>&1 | Out-String
$m = [regex]::Match($out, "SHA256:\s*([0-9A-Fa-f:]+)")
if (-not $m.Success) {
    Write-Error "SHA256 fingerprint not found in keytool output"
}
$fp = $m.Groups[1].Value.Trim().ToUpperInvariant()

$json = @"
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "ir.vinor.app",
      "sha256_cert_fingerprints": [
        "$fp"
      ]
    }
  }
]
"@

$outDir = Join-Path $root "well-known"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = Join-Path $outDir "assetlinks.json"
Set-Content -Path $outFile -Value $json -Encoding UTF8
Write-Host "Wrote $outFile"
Write-Host "SHA-256: $fp"
Write-Host "Publish to: https://vinor.ir/.well-known/assetlinks.json"
