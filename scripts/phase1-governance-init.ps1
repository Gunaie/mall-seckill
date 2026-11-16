# P1 治理初始化：向 Nacos 发布应用配置与 Sentinel 规则
# 用法: powershell -ExecutionPolicy Bypass -File scripts/phase1-governance-init.ps1
param(
    [string]$Nacos = "http://localhost:8848"
)

$ErrorActionPreference = "Stop"

Write-Host "==> 等待 Nacos 就绪: $Nacos"
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = & curl.exe -s "$Nacos/nacos/v1/console/health/readiness" 2>$null
        if ($r -match "UP|ok") { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw "Nacos 未就绪，请先 docker compose up -d nacos" }

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
    Write-Host "    $dataId ($group) -> $out"
    if ($out -notmatch "true") { throw "发布失败: $dataId" }
}

$appYaml = @"
# 交易中台动态配置（Nacos 优先于本地 application-prod.yml）
mall:
  seckill:
    # 本地售罄标记：可在 Nacos 控制台动态切换，无需重启
    local-mark-enabled: true
    path-ttl-seconds: 60
"@

$flowRules = @"
[
  {
    "resource": "seckillExecute",
    "limitApp": "default",
    "grade": 1,
    "count": 5000,
    "strategy": 0,
    "controlBehavior": 0,
    "clusterMode": false
  }
]
"@

$paramFlowRules = @"
[
  {
    "resource": "seckillExecute",
    "paramIdx": 0,
    "count": 2000,
    "grade": 1,
    "durationInSec": 1,
    "controlBehavior": 0,
    "clusterMode": false
  }
]
"@

$degradeRules = @"
[]
"@

Write-Host "==> 发布应用配置"
Publish "mall-seckill.yaml" "DEFAULT_GROUP" "yaml" $appYaml

Write-Host "==> 发布 Sentinel 规则"
Publish "mall-seckill-flow-rules.json" "SENTINEL_GROUP" "json" $flowRules
Publish "mall-seckill-param-flow-rules.json" "SENTINEL_GROUP" "json" $paramFlowRules
Publish "mall-seckill-degrade-rules.json" "SENTINEL_GROUP" "json" $degradeRules

Write-Host "GOVERNANCE_INIT_OK"
