# Phase 7 end-to-end smoke test through Nginx (8090)
# Prereq: docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d
$ErrorActionPreference = "Stop"
$base = "http://localhost:8090"

function PostJson($path, $json, $token) {
    # PS 5.1 向原生命令传参会吞掉 JSON 双引号，统一用临时文件承载 body
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $json, [System.Text.UTF8Encoding]::new($false))
    $a = @("-s", "-X", "POST", "$base$path", "-H", "Content-Type: application/json", "--data-binary", "@$tmp")
    if ($token) { $a += @("-H", "Authorization: Bearer $token") }
    try {
        return ((& curl.exe @a) | ConvertFrom-Json)
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}
function GetJson($path, $token) {
    $a = @("-s", "$base$path")
    if ($token) { $a += @("-H", "Authorization: Bearer $token") }
    return ((& curl.exe @a) | ConvertFrom-Json)
}

Write-Host "==> 1. admin login"
$login = PostJson "/api/auth/login" '{"username":"admin","password":"admin123"}'
$token = $login.data.accessToken
if (-not $token) { throw "login failed" }
Write-Host "    token ok"

Write-Host "==> 2. create activity/session/seckill-sku and preheat"
PostJson "/api/admin/seckill/activities" '{"name":"phase7-smoke"}' $token | Out-Null
$activity = (GetJson "/api/admin/seckill/activities" $token).data[0]
$start = (Get-Date).AddMinutes(-2).ToString("yyyy-MM-ddTHH:mm:ss")
$end = (Get-Date).AddHours(4).ToString("yyyy-MM-ddTHH:mm:ss")
$sessJson = '{"activityId":' + $activity.id + ',"name":"phase7-session","startTime":"' + $start + '","endTime":"' + $end + '"}'
PostJson "/api/admin/seckill/sessions" $sessJson $token | Out-Null
$sessions = GetJson "/api/seckill/sessions" $token
$session = $sessions.data | Where-Object { $_.name -eq "phase7-session" } | Select-Object -First 1
$skuJson = '{"sessionId":' + $session.id + ',"skuId":1,"seckillPrice":1.00,"seckillStock":1000}'
PostJson "/api/admin/seckill/skus" $skuJson $token | Out-Null
$skus = GetJson "/api/admin/seckill/skus?sessionId=$($session.id)" $token
$seckillSkuId = $skus.data[0].id
Write-Host "    seckillSkuId=$seckillSkuId sessionId=$($session.id)"
PostJson "/api/admin/seckill/skus/$seckillSkuId/upload" '{}' $token | Out-Null
Write-Host "    preheat ok"

Write-Host "==> 3. three-step flow: path -> execute -> result"
$pathResp = PostJson "/api/seckill/$seckillSkuId/path" '{}' $token
$path = $pathResp.data
if ($path -notmatch '^[a-f0-9]{32}$') { throw "path exchange failed" }
Write-Host "    path=$path"

$execBody = '{"receiverName":"phase7","receiverPhone":"13800000000","receiverAddress":"phase7-address"}'
$exec = PostJson "/api/seckill/$seckillSkuId/$path/execute" $execBody $token
Write-Host "    execute code=$($exec.code) status=$($exec.data.status)"
if ($exec.data.status -ne "QUEUING") { throw "not queued" }

$orderId = $null
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 1
    $r = GetJson "/api/seckill/$seckillSkuId/result" $token
    if ($r.data.status -eq "SUCCESS") { $orderId = $r.data.orderId; break }
    if ($r.data.status -eq "FAIL") { throw "consume failed" }
}
if (-not $orderId) { throw "no SUCCESS within 15s" }
Write-Host "    async order created orderId=$orderId"

Write-Host "==> 4. one-person-one-order: repeat execute must be rejected"
$dup = PostJson "/api/seckill/$seckillSkuId/$path/execute" $execBody $token
Write-Host "    repeat code=$($dup.code) message=$($dup.message)"
if ($dup.code -eq 200) { throw "one-person-one-order broken" }

Write-Host "==> 5. anonymous request must be 401"
$anon = & curl.exe -s -o NUL -w "%{http_code}" "$base/api/seckill/$seckillSkuId/result"
Write-Host "    HTTP $anon"
if ($anon -ne "401") { throw "expected 401" }

Write-Host ""
Write-Host "SMOKE_OK seckillSkuId=$seckillSkuId orderId=$orderId"
