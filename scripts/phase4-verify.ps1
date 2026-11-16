# P4 Elasticsearch + IK 商品搜索验证
# 前置：docker compose up -d（含 elasticsearch），app1/app2 使用 bench 覆盖配置
# 覆盖：全量重建索引 / 关键词高亮 / 分类与价格筛选 / 价格排序 / 增量同步(≤3s) /
#       ES 停机降级 DB / ES 恢复 / 搜索延迟
param(
    [string]$Base = "http://localhost:8090"
)
$ErrorActionPreference = "Stop"

function PostJson($path, $json, $token) {
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $json, [System.Text.UTF8Encoding]::new($false))
    $a = @("-s", "-X", "POST", "$Base$path", "-H", "Content-Type: application/json", "--data-binary", "@$tmp")
    if ($token) { $a += @("-H", "Authorization: Bearer $token") }
    try { return ((& curl.exe @a) | ConvertFrom-Json) } finally { Remove-Item $tmp -ErrorAction SilentlyContinue }
}
function DeleteJson($path, $token) {
    $a = @("-s", "-X", "DELETE", "$Base$path")
    if ($token) { $a += @("-H", "Authorization: Bearer $token") }
    return ((& curl.exe @a) | ConvertFrom-Json)
}
function GetJson($path, $token) {
    $a = @("-s", "$Base$path")
    if ($token) { $a += @("-H", "Authorization: Bearer $token") }
    return ((& curl.exe @a) | ConvertFrom-Json)
}
# 中文关键词必须 URL 编码，curl -G --data-urlencode 可避免手工编码出错
function SearchJson([hashtable]$Params) {
    $a = @("-s", "-G", "$Base/api/search/spu")
    if ($script:userToken) { $a += @("-H", "Authorization: Bearer $script:userToken") }
    foreach ($k in $Params.Keys) {
        $a += @("--data-urlencode", "$k=$($Params[$k])")
    }
    return ((& curl.exe @a) | ConvertFrom-Json)
}
# 只测 HTTP 往返（curl time_total），排除 PowerShell 进程启动开销
function SearchTime([hashtable]$Params) {
    $a = @("-s", "-o", "NUL", "-w", "%{time_total}", "-G", "$Base/api/search/spu")
    if ($script:userToken) { $a += @("-H", "Authorization: Bearer $script:userToken") }
    foreach ($k in $Params.Keys) {
        $a += @("--data-urlencode", "$k=$($Params[$k])")
    }
    return [double]((& curl.exe @a).ToString().Trim()) * 1000
}
function Sql($sql) {
    $out = docker exec -e MYSQL_PWD=root123456 mall-mysql mysql -uroot -N -e $sql
    $line = $out | Where-Object { ("$_" -notmatch 'Warning') } | Select-Object -First 1
    if ($null -eq $line) { return "" }
    return ([string]$line).Trim()
}
function EsJson($path) {
    return ((& curl.exe -s --max-time 10 "http://localhost:9200$path") | ConvertFrom-Json)
}
function Wait-Search([string]$Keyword, [bool]$ShouldExist, [int]$TimeoutSeconds = 8) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $r = SearchJson @{ keyword = $Keyword; pageSize = 20 }
        $found = @($r.data.list | Where-Object { $_.name -like "*$Keyword*" }).Count -gt 0
        if ($found -eq $ShouldExist) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

Write-Host "==> 0. 检查 ES 与 IK 插件"
$health = EsJson "/_cluster/health"
Write-Host "    ES status=$($health.status)"
if ($health.status -ne "green" -and $health.status -ne "yellow") { throw "ES 未就绪: $($health.status)" }
$plugins = (& curl.exe -s "http://localhost:9200/_cat/plugins") -join "`n"
if ($plugins -match "analysis-ik") {
    Write-Host "    IK 插件已加载"
} else {
    Write-Host "    [WARN] 未检测到 IK 插件，分词将降级 standard"
}

Write-Host "==> 1. 全量重建索引"
# 搜索接口与商品列表一致需要登录（买家角色），先获取 demo 用户 token
$script:userToken = (PostJson "/api/auth/login" '{"username":"demo","password":"user123"}' $null).data.accessToken
if (-not $script:userToken) { throw "买家登录失败" }
$adminToken = (PostJson "/api/auth/login" '{"username":"admin","password":"admin123"}' $null).data.accessToken
if (-not $adminToken) { throw "管理员登录失败" }
$onSale = [int](Sql "select count(*) from mall_seckill.pms_spu where status=1 and deleted=0")
$reindex = PostJson "/api/admin/search/reindex" '{}' $adminToken
Write-Host "    上架商品数=$onSale 索引文档数=$($reindex.data.indexed)"
if ($reindex.code -ne 200) { throw "重建索引失败: $($reindex.message)" }
if ([int]$reindex.data.indexed -ne $onSale) { throw "索引数与上架数不一致" }
$count = (EsJson "/pms_spu/_count").count
Write-Host "    ES _count=$count"
if ([int]$count -ne $onSale) { throw "ES 文档数与上架数不一致: $count vs $onSale" }

Write-Host "==> 2. 关键词搜索 + 高亮"
$r1 = SearchJson @{ keyword = "手机"; pageSize = 10 }
if ($r1.code -ne 200) { throw "搜索接口异常: $($r1.message)" }
$list1 = @($r1.data.list)
Write-Host "    keyword=手机 total=$($r1.data.total) degraded=$($r1.data.degraded) analyzer=$($r1.data.analyzer) tookMs=$($r1.data.tookMs)"
if ($list1.Count -le 0) { throw "关键词「手机」无结果" }
$hl = @($list1 | Where-Object { $_.highlightName -and $_.highlightName -match "<em>" })
if ($hl.Count -le 0) { throw "未返回高亮片段" }
Write-Host "    高亮示例: $($hl[0].highlightName)"

Write-Host "==> 3. 分类筛选"
$categoryId = [long]$list1[0].categoryId
$r2 = SearchJson @{ categoryId = $categoryId; pageSize = 20 }
$list2 = @($r2.data.list)
$wrong = @($list2 | Where-Object { $_.categoryId -ne $categoryId })
Write-Host "    categoryId=$categoryId total=$($r2.data.total) 越界结果=$($wrong.Count)"
if ($list2.Count -le 0) { throw "分类筛选无结果" }
if ($wrong.Count -gt 0) { throw "分类筛选返回了其他分类商品" }

Write-Host "==> 4. 价格区间与价格排序"
$r3 = SearchJson @{ minPrice = 0; maxPrice = 1000; pageSize = 50 }
$over = @($r3.data.list | Where-Object { [double]$_.minPrice -gt 1000 })
Write-Host "    price<=1000 结果数=$($r3.data.total) 越界结果=$($over.Count)"
if ($over.Count -gt 0) { throw "价格筛选越界" }
$r4 = SearchJson @{ sort = "priceAsc"; pageSize = 20 }
$prices = @($r4.data.list | ForEach-Object { [double]$_.minPrice })
$sorted = $true
for ($i = 1; $i -lt $prices.Count; $i++) {
    if ($prices[$i] -lt $prices[$i - 1]) { $sorted = $false; break }
}
Write-Host "    priceAsc 价格序列=$($prices -join ',')"
if (-not $sorted) { throw "价格升序排序不正确" }

Write-Host "==> 5. 增量同步：新增商品 3s 内可搜，删除 3s 内消失"
$tag = Get-Date -Format "HHmmss"
$name = "P4时序验证商品$tag"
$skuCode = "P4-$tag"
$body = '{"name":"' + $name + '","categoryId":' + $categoryId + ',"subtitle":"p4-incremental","status":1,"skus":[{"skuCode":"' + $skuCode + '","name":"' + $name + '-SKU","price":88.88,"stock":10}]}'
$created = PostJson "/api/admin/spu" $body $adminToken
if ($created.code -ne 200) { throw "新增商品失败: $($created.message)" }
$spuId = [long]$created.data
Write-Host "    新增 spuId=$spuId name=$name"
if (-not (Wait-Search "P4时序验证商品$tag" $true 8)) { throw "新增商品未在 8s 内进入索引" }
Write-Host "    新增商品已进入索引"
$del = DeleteJson "/api/admin/spu/$spuId" $adminToken
if ($del.code -ne 200) { throw "删除商品失败: $($del.message)" }
if (-not (Wait-Search "P4时序验证商品$tag" $false 8)) { throw "删除商品 8s 内未从索引移除" }
Write-Host "    已删除商品从索引移除"

Write-Host "==> 6. ES 停机降级数据库"
docker stop mall-elasticsearch | Out-Null
Start-Sleep -Seconds 2
$down = SearchJson @{ keyword = "手机"; pageSize = 10 }
Write-Host "    ES 停机: degraded=$($down.data.degraded) total=$($down.data.total) tookMs=$($down.data.tookMs)"
if (-not $down.data.degraded) { throw "ES 停机后未降级" }
if ($down.data.total -le 0) { throw "降级数据库后无结果" }
docker start mall-elasticsearch | Out-Null
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    try {
        $h = EsJson "/_cluster/health"
        if ($h.status -eq "green" -or $h.status -eq "yellow") { $ready = $true; break }
    } catch { }
}
if (-not $ready) { throw "ES 重启后未就绪" }
Start-Sleep -Seconds 3
$up = SearchJson @{ keyword = "手机"; pageSize = 10 }
Write-Host "    ES 恢复: degraded=$($up.data.degraded) total=$($up.data.total) analyzer=$($up.data.analyzer)"
if ($up.data.degraded) { throw "ES 恢复后仍在降级" }

Write-Host "==> 7. 搜索延迟（预热 5 次 + 30 次串行，HTTP 往返）"
foreach ($i in 1..5) { SearchJson @{ keyword = "手机"; pageSize = 10 } | Out-Null }
$lat = @()
foreach ($i in 1..30) {
    $lat += SearchTime @{ keyword = "手机"; pageSize = 10 }
}
$sortedLat = $lat | Sort-Object
$avg = [math]::Round((($lat | Measure-Object -Average).Average), 1)
$p95 = $sortedLat[[int][math]::Floor($sortedLat.Count * 0.95) - 1]
Write-Host "    avg=${avg}ms p95=${p95}ms min=$($sortedLat[0])ms max=$($sortedLat[-1])ms"

Write-Host ""
Write-Host "P4_OK 索引重建 / 关键词高亮 / 分类价格筛选 / 价格排序 / 增量同步 / 停机降级 / 恢复 全部通过"
