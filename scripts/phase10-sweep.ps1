# Phase 10 concurrency sweep: same reject path, varying concurrency
param([long]$SkuId = 4, [int]$Total = 4000)
$ErrorActionPreference = "Stop"
$base = "http://localhost:8090"
$loginBody = '{"username":"admin","password":"admin123"}'
$t = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($t, $loginBody, [System.Text.UTF8Encoding]::new($false))
$login = (& curl.exe -s -X POST "$base/api/auth/login" -H "Content-Type: application/json" --data-binary "@$t" | ConvertFrom-Json)
Remove-Item $t
$token = $login.data.accessToken

$concurrencies = @(50, 100, 200, 300, 400)
foreach ($c in $concurrencies) {
    Write-Host ""
    Write-Host "===== concurrency=$c total=$Total ====="
    java "$PSScriptRoot/SeckillBench.java" $SkuId $Total $c $token $base | Select-Object -Last 3
}
