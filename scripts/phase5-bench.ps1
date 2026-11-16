# P5 benchmark matrix: sold-out reject path + success path (one order per user)
# The load generator runs inside the compose network by default: traffic through the
# Windows port proxy adds queueing noise and understates server throughput.
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -Mode reject
#   powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -Mode success -SuccessUsers 1000
#   powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -HostClient
param(
    [long]$SkuId = 4,
    [ValidateSet("all", "reject", "success")][string]$Mode = "all",
    [int]$RejectTotal = 6000,
    [int]$SuccessUsers = 1000,
    [int[]]$RejectConcurrency = @(100, 200, 300),
    [int]$SuccessConcurrency = 100,
    [switch]$HostClient,
    [string]$BenchImage = "maven:3.9-eclipse-temurin-21"
)
$ErrorActionPreference = "Stop"
$benchDir = (Resolve-Path "$PSScriptRoot").Path
$tokenFile = Join-Path $env:TEMP "mall-p5-tokens.txt"

function Invoke-CurlJson($method, $path, $body, $token) {
    $tmp = [System.IO.Path]::GetTempFileName()
    if ($body) { [System.IO.File]::WriteAllText($tmp, $body, [System.Text.UTF8Encoding]::new($false)) }
    $curlArgs = @("-s", "-X", $method, "http://localhost:8090$path", "-H", "Content-Type: application/json")
    if ($token) { $curlArgs += @("-H", "Authorization: Bearer $token") }
    if ($body) { $curlArgs += @("--data-binary", "@$tmp") }
    $text = & curl.exe @curlArgs
    Remove-Item $tmp -ErrorAction SilentlyContinue
    return $text
}

function Get-RejectToken() {
    # Fixed bench user: stock is 0 in reject mode, so the user never joins the ordered set
    $body = '{"username":"p5_reject","password":"123456","nickname":"p5"}'
    $resp = Invoke-CurlJson "POST" "/api/auth/register" $body $null | ConvertFrom-Json
    if ($resp.data.accessToken) { return $resp.data.accessToken }
    $resp = Invoke-CurlJson "POST" "/api/auth/login" '{"username":"p5_reject","password":"123456"}' $null | ConvertFrom-Json
    if (-not $resp.data.accessToken) { throw "p5_reject login failed" }
    return $resp.data.accessToken
}

function Get-AdminToken() {
    $resp = Invoke-CurlJson "POST" "/api/auth/login" '{"username":"admin","password":"admin123"}' $null | ConvertFrom-Json
    if (-not $resp.data.accessToken) { throw "admin login failed" }
    return $resp.data.accessToken
}

# Run the load generator: inside the compose network by default, host JVM with -HostClient
function Invoke-Bench([string[]]$BenchArgs, [string]$TokenPathOnHost) {
    if ($HostClient) {
        & java "$benchDir/SeckillBench.java" @BenchArgs
        return
    }
    $dockerArgs = @("run", "--rm", "--network", "mall-seckill_mall-net",
        "-v", "${benchDir}:/bench")
    if ($TokenPathOnHost) {
        $dockerArgs += @("-v", "$(Split-Path $TokenPathOnHost -Parent):/tokens")
    }
    $dockerArgs += @("-w", "/bench", $BenchImage, "java", "SeckillBench.java")
    $dockerArgs += $BenchArgs
    & docker @dockerArgs
}

$target = if ($HostClient) { "http://localhost:8090" } else { "http://nginx" }

Write-Host "===== P5 bench: sku=$SkuId target=$target ====="

if ($Mode -eq "all" -or $Mode -eq "reject") {
    $token = Get-RejectToken
    Write-Host "----- reject path: redis stock -> 0 -----"
    & docker exec mall-redis redis-cli -a mall123456 --no-auth-warning set "seckill:stock:$SkuId" 0 | Out-Null
    Invoke-Bench -BenchArgs @("reject", "$SkuId", "300", "20", $token, $target) | Select-Object -Last 4
    foreach ($c in $RejectConcurrency) {
        Write-Host "----- reject path c=$c -----"
        Invoke-Bench -BenchArgs @("reject", "$SkuId", "$RejectTotal", "$c", $token, $target) | Select-Object -Last 4
    }
}

if ($Mode -eq "all" -or $Mode -eq "success") {
    Write-Host "----- success path: re-preheat (restore stock, clear local marks) -----"
    $admin = Get-AdminToken
    # Two calls so both app instances handle one preheat and clear their own local mark
    1..4 | ForEach-Object {
        $resp = Invoke-CurlJson "POST" "/api/admin/seckill/skus/$SkuId/upload" "{}" $admin | ConvertFrom-Json
    }
    Write-Host "preheat code=$($resp.code) stock=$(docker exec mall-redis redis-cli -a mall123456 --no-auth-warning get seckill:stock:$SkuId)"
    Start-Sleep -Seconds 11

    Write-Host "----- seed $SuccessUsers users -----"
    $seedPath = if ($HostClient) { $tokenFile } else { "/tokens/$(Split-Path $tokenFile -Leaf)" }
    Invoke-Bench -BenchArgs @("seed", "$SuccessUsers", $seedPath, $target) -TokenPathOnHost $tokenFile
    # One order per user, so the success run is single-round
    Write-Host "----- success path c=$SuccessConcurrency -----"
    Invoke-Bench -BenchArgs @("success", "$SkuId", "$SuccessUsers", "$SuccessConcurrency", $seedPath, $target) -TokenPathOnHost $tokenFile | Select-Object -Last 5
}

Write-Host "P5_BENCH_DONE"
