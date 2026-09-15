$ErrorActionPreference = "Stop"
$base = "http://localhost:8090"
$loginBody = '{"username":"admin","password":"admin123"}'
$t = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($t, $loginBody, [System.Text.UTF8Encoding]::new($false))
$login = (& curl.exe -s -X POST "$base/api/auth/login" -H "Content-Type: application/json" --data-binary "@$t" | ConvertFrom-Json)
Remove-Item $t
$token = $login.data.accessToken
if (-not $token) { throw "login failed" }

function Send($method, $path, $body) {
    $tmp = [System.IO.Path]::GetTempFileName()
    if ($body) { [System.IO.File]::WriteAllText($tmp, $body, [System.Text.UTF8Encoding]::new($false)) }
    $a = @("-s", "-X", $method, "$base$path", "-H", "Content-Type: application/json", "-H", "Authorization: Bearer $token")
    if ($body) { $a += @("--data-binary", "@$tmp") }
    $r = (& curl.exe @a | ConvertFrom-Json)
    Remove-Item $tmp -ErrorAction SilentlyContinue
    return $r
}

$activity = (Send GET "/api/admin/seckill/activities" $null).data | Where-Object { $_.name -eq "phase10-bench" } | Select-Object -First 1
Write-Host "activityId=$($activity.id)"

$session = (Send GET "/api/seckill/sessions" $null).data | Where-Object { $_.name -eq "phase10-sess" } | Select-Object -First 1
Write-Host "sessionId=$($session.id)"

# 20w 库存足够支撑压测中大量排队成功请求
$skuBody = '{"sessionId":' + $session.id + ',"skuId":1,"seckillPrice":1.00,"seckillStock":200000}'
Send POST "/api/admin/seckill/skus" $skuBody | Out-Null

$list = (Send GET "/api/admin/seckill/skus?sessionId=$($session.id)" $null).data
$ssid = $list[0].id
Write-Host "seckillSkuId=$ssid"

Send POST "/api/admin/seckill/skus/$ssid/upload" '{}' | Out-Null
Write-Host "preheat ok"

# 验证库存预热到 Redis
$pathResp = Send POST "/api/seckill/$ssid/path" '{}'
Write-Host "path exchange: code=$($pathResp.code) path=$($pathResp.data)"
Write-Host "SETUP_OK seckillSkuId=$ssid"
