# Phase 7 benchmark: SeckillBench through Nginx (8090), single token -> reject-path throughput
# Usage: powershell phase7-bench.ps1 -SkuId 2
param(
    [long]$SkuId = 2,
    [int]$Total = 3000,
    [int]$Concurrency = 200
)
$ErrorActionPreference = "Stop"
$base = "http://localhost:8090"

$loginBody = '{"username":"admin","password":"admin123"}'
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $loginBody, [System.Text.UTF8Encoding]::new($false))
$login = (& curl.exe -s -X POST "$base/api/auth/login" -H "Content-Type: application/json" --data-binary "@$tmp" | ConvertFrom-Json)
Remove-Item $tmp
$token = $login.data.accessToken
if (-not $token) { throw "login failed" }
Write-Host "login ok, warmup 500/50 ..."
java "$PSScriptRoot/SeckillBench.java" $SkuId 500 50 $token $base | Select-Object -Last 3
Write-Host ""
Write-Host "===== measured run 1 ($Total / c=$Concurrency) ====="
java "$PSScriptRoot/SeckillBench.java" $SkuId $Total $Concurrency $token $base | Select-Object -Last 3
Write-Host ""
Write-Host "===== measured run 2 ($Total / c=$Concurrency) ====="
java "$PSScriptRoot/SeckillBench.java" $SkuId $Total $Concurrency $token $base | Select-Object -Last 3
