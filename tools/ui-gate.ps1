# UI gate (P5): count hardcoded literal metrics in UI layer and compare to baseline.
# Shrink-only metrics (font/shape/color/dp literals, null descriptions) must not grow;
# contentType is inverse (heterogeneous list reuse) and must not shrink.
# Usage:
#   powershell -File tools\ui-gate.ps1                  # compare to baseline, exit 1 on regression
#   powershell -File tools\ui-gate.ps1 -UpdateBaseline  # write current counts as new baseline
param([switch]$UpdateBaseline)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$src = Join-Path $root "app\src\main\java"
$baselineFile = Join-Path $PSScriptRoot "ui-gate-baseline.json"

$metrics = [ordered]@{
  fontLiteral   = 'fontSize\s*=\s*\d+\.sp'
  radiusLiteral = 'RoundedCornerShape\(\d+\.dp\)'
  colorLiteral  = 'Color\(0x[0-9A-Fa-f]{8}\)'
  descNull      = 'contentDescription\s*=\s*null'
  dpLiteral     = '\b\d+\.dp\b'
  spLiteral     = '\b\d+\.sp\b'
  contentType   = 'contentType\s*='
}

$files = @(Get-ChildItem -Path $src -Recurse -Filter *.kt)
if ($files.Count -eq 0) { throw "no .kt files under $src" }

$current = [ordered]@{}
foreach ($k in $metrics.Keys) {
  $count = 0
  foreach ($f in $files) {
    $m = Select-String -Path $f.FullName -Pattern $metrics[$k] -AllMatches
    foreach ($hit in $m) { $count += $hit.Matches.Count }
  }
  $current[$k] = $count
}

if ($UpdateBaseline) {
  $current | ConvertTo-Json | Set-Content -Path $baselineFile -Encoding UTF8
  Write-Host "Baseline updated: $baselineFile"
  $current.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-14} {1}" -f $_.Key, $_.Value) }
  exit 0
}

if (-not (Test-Path $baselineFile)) {
  Write-Host "No baseline; run with -UpdateBaseline first."
  exit 2
}

$baseline = Get-Content $baselineFile -Raw | ConvertFrom-Json
$fail = $false
foreach ($k in $metrics.Keys) {
  $b = [int]$baseline.$k
  $c = [int]$current[$k]
  $delta = $c - $b
  $ok = if ($k -eq 'contentType') { $delta -ge 0 } else { $delta -le 0 }
  $mark = if ($ok) { "OK  " } else { "FAIL" }
  Write-Host ("{0} {1,-14} baseline={2,-6} now={3,-6} delta={4}" -f $mark, $k, $b, $c, $delta)
  if (-not $ok) { $fail = $true }
}
if ($fail) { Write-Host "ui-gate: regression (counts must not grow). Fix or re-baseline with -UpdateBaseline if intended." }
else { Write-Host "ui-gate: pass." }
exit $(if ($fail) { 1 } else { 0 })
