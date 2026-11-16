# Phase 0 基线压测：售罄拒绝路径
# 用法: powershell -ExecutionPolicy Bypass -File scripts/phase0-baseline.ps1 [-SkuId 4] [-Total 20000] [-Concurrency 400] [-Base http://localhost:8090]
param(
    [long]$SkuId = 4,
    [int]$Total = 20000,
    [int]$Concurrency = 400,
    [string]$Base = "http://localhost:8090"
)

$ErrorActionPreference = "Stop"

function PostJson($path, $json, $token) {
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $json, [System.Text.UTF8Encoding]::new($false))
    $a = @("-s", "-X", "POST", "$Base$path", "-H", "Content-Type: application/json", "--data-binary", "@$tmp")
    if ($token) { $a += @("-H", "Authorization: Bearer $token") }
    try {
        return ((& curl.exe @a) | ConvertFrom-Json)
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}

Write-Host "==> 0. 恢复压测前置：预热 sku=$SkuId"
$admin = PostJson "/api/auth/login" '{"username":"admin","password":"admin123"}' $null
$adminToken = $admin.data.accessToken
if (-not $adminToken) { throw "admin login failed" }
PostJson "/api/admin/seckill/skus/$SkuId/upload" '{}' $adminToken | Out-Null

Write-Host "==> 1. 注册独立压测用户（避开一人一单判重）"
$user = "bench" + (Get-Date -Format "MMddHHmmss")
$reg = PostJson "/api/auth/register" ("{`"username`":`"$user`",`"password`":`"bench123`",`"nickname`":`"bench`"}") $null
$token = $reg.data.accessToken
if (-not $token) { throw "register failed: $($reg | ConvertTo-Json -Compress)" }
Write-Host "    user=$user"

Write-Host "==> 2. 将 Redis 库存置 0（售罄路径）"
docker exec mall-redis redis-cli -a mall123456 --no-auth-warning SET "seckill:stock:$SkuId" 0 | Out-Null

Write-Host "==> 3. 预热本地售罄标记"
$path = (PostJson "/api/seckill/$SkuId/path" '{}' $token).data
if (-not $path) { throw "path exchange failed" }
PostJson "/api/seckill/$SkuId/$path/execute" '{"receiverName":"bench","receiverPhone":"13800000000","receiverAddress":"bench"}' $token | Out-Null
Start-Sleep -Seconds 1

Write-Host "==> 4. 压测 sku=$SkuId total=$Total concurrency=$Concurrency"
java scripts/SeckillBench.java $SkuId $Total $Concurrency $token $Base

Write-Host "==> 5. 恢复库存"
PostJson "/api/admin/seckill/skus/$SkuId/upload" '{}' $adminToken | Out-Null
Write-Host "BASELINE_DONE"
