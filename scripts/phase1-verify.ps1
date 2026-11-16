# P1 治理验证：Nacos 动态配置 + Sentinel 流控
# 用法: powershell -ExecutionPolicy Bypass -File scripts/phase1-verify.ps1
param(
    [string]$Base = "http://localhost:8090",
    [string]$Nacos = "http://localhost:8848",
    [string]$Prometheus = "http://localhost:9090",
    [long]$SkuId = 4
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

function Publish($dataId, $group, $type, $content) {
    $f = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($f, $content, [System.Text.UTF8Encoding]::new($false))
    try {
        $out = & curl.exe -s -X POST "$Nacos/nacos/v1/cs/configs" `
            --data-urlencode "dataId=$dataId" `
            --data-urlencode "group=$group" `
            --data-urlencode "type=$type" `
            --data-urlencode "content@$f"
    } finally {
        Remove-Item $f -ErrorAction SilentlyContinue
    }
    if ($out -notmatch "true") { throw "发布失败: $dataId" }
}

function ShowMetrics($title) {
    Write-Host "--- $title ---"
    $q = [uri]::EscapeDataString('sum by (result) (mall_seckill_execute_total)')
    $r = (& curl.exe -s "$Prometheus/api/v1/query?query=$q") | ConvertFrom-Json
    if ($r.data.result.Count -eq 0) {
        Write-Host "  (暂无指标，等待下一次 scrape)"
    } else {
        $r.data.result | ForEach-Object { Write-Host "  $($_.metric.result)=$($_.value[1])" }
    }
}

$yamlOn = @"
mall:
  seckill:
    local-mark-enabled: true
    path-ttl-seconds: 60
"@

$yamlOff = @"
mall:
  seckill:
    local-mark-enabled: false
    path-ttl-seconds: 60
"@

$flowHigh = @"
[
  { "resource": "seckillExecute", "limitApp": "default", "grade": 1, "count": 5000, "strategy": 0, "controlBehavior": 0, "clusterMode": false }
]
"@

$flowLow = @"
[
  { "resource": "seckillExecute", "limitApp": "default", "grade": 1, "count": 5, "strategy": 0, "controlBehavior": 0, "clusterMode": false }
]
"@

Write-Host "==> 1. 准备售罄场景"
$admin = PostJson "/api/auth/login" '{"username":"admin","password":"admin123"}' $null
$adminToken = $admin.data.accessToken
PostJson "/api/admin/seckill/skus/$SkuId/upload" '{}' $adminToken | Out-Null
$user = "gov" + (Get-Date -Format "MMddHHmmss")
$reg = PostJson "/api/auth/register" ("{`"username`":`"$user`",`"password`":`"bench123`",`"nickname`":`"gov`"}") $null
$token = $reg.data.accessToken
docker exec mall-redis redis-cli -a mall123456 --no-auth-warning SET "seckill:stock:$SkuId" 0 | Out-Null
$path = (PostJson "/api/seckill/$SkuId/path" '{}' $token).data
$body = '{"receiverName":"gov","receiverPhone":"13800000000","receiverAddress":"gov"}'

Write-Host "==> 2. 两次售罄请求：第二次应命中本地标记"
PostJson "/api/seckill/$SkuId/$path/execute" $body $token | Out-Null
Start-Sleep -Seconds 1
PostJson "/api/seckill/$SkuId/$path/execute" $body $token | Out-Null
Start-Sleep -Seconds 18
ShowMetrics "本地标记 ON"

Write-Host "==> 3. Nacos 动态关闭本地标记"
Publish "mall-seckill.yaml" "DEFAULT_GROUP" "yaml" $yamlOff
Start-Sleep -Seconds 8
PostJson "/api/seckill/$SkuId/$path/execute" $body $token | Out-Null
Start-Sleep -Seconds 18
ShowMetrics "本地标记 OFF（应只增加 sold_out，不再增加 sold_out_local）"

Write-Host "==> 4. Sentinel 流控：阈值降到 5 QPS"
Publish "mall-seckill-flow-rules.json" "SENTINEL_GROUP" "json" $flowLow
Start-Sleep -Seconds 8
java scripts/SeckillBench.java $SkuId 200 20 $token $Base
Publish "mall-seckill-flow-rules.json" "SENTINEL_GROUP" "json" $flowHigh

Write-Host "==> 5. 恢复配置与库存"
Publish "mall-seckill.yaml" "DEFAULT_GROUP" "yaml" $yamlOn
PostJson "/api/admin/seckill/skus/$SkuId/upload" '{}' $adminToken | Out-Null
Write-Host "GOVERNANCE_VERIFY_DONE"
