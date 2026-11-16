# =============================================================
# P3 验证：ShardingSphere 订单域分库分表
# 前置：
#   1. docker compose up -d 全栈运行（含 mysql-order-0/1、app1/app2、nginx）
#   2. 验证期间使用压测覆盖配置关闭验证码/限流：
#      docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d app1 app2
# 覆盖：
#   A. 订单状态流转（支付/发货/确认收货/取消回补/超时关单回补）
#   B. 买家查询单分片路由 vs 管理端 8 分片广播（MySQL general_log 取证）
#   C. 40 个真实注册用户下单，8 个物理分片均匀分布
# 输出：P3_OK
# =============================================================
$ErrorActionPreference = "Stop"
$Base = "http://localhost:8090"
$MainContainer = "mall-mysql"
$Containers = @("mall-mysql-order0", "mall-mysql-order1")
$Dbs = @("mall_order_0", "mall_order_1")
$SkuId = 5  # 雷霆 T1 珍珠白，库存 1000，便于批量下单

function Fail([string]$msg) {
    Write-Host "P3_FAIL: $msg" -ForegroundColor Red
    exit 1
}

function Assert-True([bool]$cond, [string]$msg) {
    if (-not $cond) { Fail $msg }
}

function To-Text($value) {
    if ($null -eq $value) { return "" }
    if ($value -is [array]) { return ($value -join "`n") }
    return [string]$value
}

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [string]$Token)
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{ Uri = "$Base$Path"; Method = $Method; Headers = $headers; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($Body | ConvertTo-Json -Depth 6 -Compress)
    }
    try {
        return Invoke-RestMethod @params
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $reader.ReadToEnd()
            try { return ($text | ConvertFrom-Json) } catch { return @{ code = -1; message = $text } }
        }
        throw
    }
}

function Login([string]$Username, [string]$Password) {
    $r = Invoke-Api Post "/api/auth/login" @{ username = $Username; password = $Password } $null
    if ($r.code -ne 200) { Fail "登录失败 $Username : $($r.message)" }
    return $r.data.accessToken
}

function Register([string]$Username, [string]$Password) {
    $r = Invoke-Api Post "/api/auth/register" @{ username = $Username; password = $Password; nickname = $Username } $null
    if ($r.code -ne 200) { Fail "注册失败 $Username : $($r.message)" }
    return $r.data.accessToken
}

function Sql([string]$Container, [string]$Database, [string]$Query) {
    # 通过 MYSQL_PWD 传密码，避免 mysql CLI 的 stderr 警告在 PS5.1 下被当成终止错误
    $out = docker exec -e MYSQL_PWD=root123456 $Container mysql -uroot -N -B -e "USE $Database; $Query"
    if ($LASTEXITCODE -ne 0) { Fail "SQL 执行失败 [$Container/$Database]: $Query" }
    return $out
}

# 与 sharding.yaml 保持一致的期望分片：库=user_id%2，表=(user_id>>1)%4
function Get-ExpectedShard([long]$UserId) {
    return @{ Ds = [int]($UserId % 2); Table = [int]((($UserId -shr 1) % 4)) }
}

function Find-OrderRow([long]$OrderId) {
    foreach ($ds in 0..1) {
        foreach ($t in 0..3) {
            $row = Sql $Containers[$ds] $Dbs[$ds] "SELECT CONCAT(id,' ',user_id,' ',status) FROM oms_order_$t WHERE id=$OrderId"
            if ($row) { return @{ Ds = $ds; Table = $t; Raw = (To-Text $row) } }
        }
    }
    return $null
}

function Get-Status([long]$OrderId) {
    $row = Find-OrderRow $OrderId
    if ($null -eq $row) { return -1 }
    $parts = $row.Raw.Split(" ").Trim()
    return [int]$parts[2]
}

function Get-UserId([long]$OrderId) {
    $row = Find-OrderRow $OrderId
    if ($null -eq $row) { return -1 }
    $parts = $row.Raw.Split(" ").Trim()
    return [long]$parts[1]
}

function Clear-Cart([string]$Token) {
    $cart = Invoke-Api Get "/api/cart" $null $Token
    if ($cart.code -eq 200 -and $cart.data -and $cart.data.items) {
        foreach ($item in $cart.data.items) {
            $null = Invoke-Api Delete "/api/cart/$($item.skuId)" $null $Token
        }
    }
}

function New-Order([string]$Token) {
    $null = Invoke-Api Post "/api/cart" @{ skuId = $SkuId; quantity = 1 } $Token
    $null = Invoke-Api Put "/api/cart/selected-all" @{ selected = $true } $Token
    $tk = (Invoke-Api Get "/api/orders/token" $null $Token).data
    $r = Invoke-Api Post "/api/orders" @{
        orderToken      = $tk
        receiverName    = "P3"
        receiverPhone   = "13800138000"
        receiverAddress = "P3 分片验证地址"
    } $Token
    if ($r.code -ne 200) { Fail "下单失败: $($r.message)" }
    Clear-Cart $Token
    return $r.data.orderId
}

function Get-ShardCounts([int]$Ds, [string]$TableName) {
    $q = (0..3 | ForEach-Object { "SELECT COUNT(*) FROM ${TableName}_$_" }) -join "; "
    $out = To-Text (Sql $Containers[$Ds] $Dbs[$Ds] $q)
    return @($out.Split("`n") | Where-Object { $_ -ne "" } | ForEach-Object { [int]$_.Trim() })
}

Write-Host "==> 0. 健康检查"
$health = Invoke-Api Get "/api/health" $null $null
Assert-True ($health.code -eq 200) "网关健康检查失败"

$adminToken = Login "admin" "admin123"
$demoToken = Login "demo" "user123"

# 清理历史遗留的待支付订单，避免并发超时关单影响库存断言
$demoPage = Invoke-Api Get "/api/orders/page?pageNum=1&pageSize=50" $null $demoToken
foreach ($o in @($demoPage.data.list)) {
    if ($o.status -eq 1) { $null = Invoke-Api Post "/api/orders/$($o.id)/cancel" $null $demoToken }
}

# -------------------------------------------------------------
Write-Host "==> A1. 普通订单全链路（下单 -> 支付 -> 发货 -> 确认收货）"
Clear-Cart $demoToken
$orderA = New-Order $demoToken
$rowA = Find-OrderRow $orderA
Assert-True ($null -ne $rowA) "订单 $orderA 未落库"
$userId = [long]$rowA.Raw.Split(" ").Trim()[1]
$expected = Get-ExpectedShard $userId
Assert-True ($rowA.Ds -eq $expected.Ds -and $rowA.Table -eq $expected.Table) `
    "订单落库分片与期望不一致: 实际 ds$($rowA.Ds)/_$($rowA.Table)，期望 ds$($expected.Ds)/_$($expected.Table)"
Write-Host "    orderId=$orderA userId=$userId -> $($Dbs[$rowA.Ds]).oms_order_$($rowA.Table)"

$page = Invoke-Api Get "/api/orders/page?pageNum=1&pageSize=10" $null $demoToken
Assert-True ($page.code -eq 200 -and (@($page.data.list | Where-Object { [string]$_.id -eq [string]$orderA }).Count -ge 1)) "买家分页未返回订单"
$detail = Invoke-Api Get "/api/orders/$orderA" $null $demoToken
Assert-True ($detail.code -eq 200 -and (@($detail.data.items).Count -ge 1)) "买家订单详情/明细异常"

# 越权：admin 用买家接口查 demo 订单应 404
$cross = Invoke-Api Get "/api/orders/$orderA" $null $adminToken
Assert-True ($cross.code -ne 200) "跨用户访问未被拒绝"
Write-Host "    买家分页/详情正常，跨用户访问已拒绝"

$null = Invoke-Api Post "/api/orders/$orderA/pay" $null $demoToken
Assert-True ((Get-Status $orderA) -eq 2) "支付后状态不是已支付"
$null = Invoke-Api Post "/api/admin/orders/$orderA/ship" $null $adminToken
Assert-True ((Get-Status $orderA) -eq 4) "发货后状态不是已发货"
$null = Invoke-Api Post "/api/orders/$orderA/confirm" $null $demoToken
Assert-True ((Get-Status $orderA) -eq 5) "确认收货后状态不是已完成"
Write-Host "    状态流转 1 -> 2 -> 4 -> 5 正确"

# -------------------------------------------------------------
Write-Host "==> A2. 用户取消订单 -> 状态取消 + 普通库存回补"
$stockBefore = [int](To-Text (Sql $MainContainer "mall_seckill" "SELECT stock FROM pms_sku WHERE id=$SkuId"))
$orderB = New-Order $demoToken
$stockAfterOrder = [int](To-Text (Sql $MainContainer "mall_seckill" "SELECT stock FROM pms_sku WHERE id=$SkuId"))
Assert-True ($stockAfterOrder -eq $stockBefore - 1) "下单未扣减普通库存"
$null = Invoke-Api Post "/api/orders/$orderB/cancel" $null $demoToken
Assert-True ((Get-Status $orderB) -eq 3) "取消后状态不是已取消"
$stockAfterCancel = [int](To-Text (Sql $MainContainer "mall_seckill" "SELECT stock FROM pms_sku WHERE id=$SkuId"))
Assert-True ($stockAfterCancel -eq $stockBefore) "取消后库存未回补"
Write-Host "    取消回补正确: $stockBefore -> $stockAfterOrder -> $stockAfterCancel"

# -------------------------------------------------------------
Write-Host "==> A3. 超时未支付 -> 延迟消息自动关单 + 库存回补（最多等待 120s）"
$orderC = New-Order $demoToken
$deadline = (Get-Date).AddSeconds(120)
$closed = $false
while ((Get-Date) -lt $deadline) {
    if ((Get-Status $orderC) -eq 3) { $closed = $true; break }
    Start-Sleep -Seconds 5
}
Assert-True $closed "超时关单未在 120s 内完成"
$stockAfterTimeout = [int](To-Text (Sql $MainContainer "mall_seckill" "SELECT stock FROM pms_sku WHERE id=$SkuId"))
Assert-True ($stockAfterTimeout -eq $stockBefore) "超时关单后库存未回补"
Write-Host "    超时关单完成且库存回补: $stockAfterTimeout"

# -------------------------------------------------------------
Write-Host "==> B. 路由取证：买家单分片 vs 管理端广播（MySQL general_log）"
foreach ($c in $Containers) {
    $null = Sql $c "mysql" "SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON; TRUNCATE TABLE general_log"
}

function Collect-DistinctOrderTables {
    $pairs = @()
    foreach ($ds in 0..1) {
        $lines = Sql $Containers[$ds] "mysql" "SELECT argument FROM general_log WHERE argument LIKE '%oms_order%'"
        foreach ($line in @($lines)) {
            foreach ($m in [regex]::Matches([string]$line, "oms_order_[0-3]")) {
                $pairs += "$($Dbs[$ds])/$($m.Value)"
            }
        }
    }
    return @($pairs | Sort-Object -Unique)
}

foreach ($c in $Containers) { $null = Sql $c "mysql" "TRUNCATE TABLE general_log" }
$null = Invoke-Api Get "/api/orders/page?pageNum=1&pageSize=5" $null $demoToken
$buyerDistinct = @(Collect-DistinctOrderTables)
Write-Host "    买家查询命中物理分片: $($buyerDistinct -join ', ')"
Assert-True ($buyerDistinct.Count -eq 1) "买家查询未做到单分片路由（命中 $($buyerDistinct.Count) 个分片）"

foreach ($c in $Containers) { $null = Sql $c "mysql" "TRUNCATE TABLE general_log" }
$null = Invoke-Api Get "/api/admin/orders/page?pageNum=1&pageSize=5" $null $adminToken
$adminDistinct = @(Collect-DistinctOrderTables)
Write-Host "    管理端广播命中物理分片数: $($adminDistinct.Count)"
Assert-True ($adminDistinct.Count -eq 8) "管理端未广播到 8 个分片（命中 $($adminDistinct.Count) 个）"

foreach ($c in $Containers) { $null = Sql $c "mysql" "SET GLOBAL general_log=OFF" }

# -------------------------------------------------------------
Write-Host "==> C. 8 分片均衡：40 个真实注册用户下单（清空订单分片后统计）"
foreach ($ds in 0..1) {
    $sql = (0..3 | ForEach-Object { "TRUNCATE TABLE oms_order_$_; TRUNCATE TABLE oms_order_item_$_;" }) -join " "
    $null = Sql $Containers[$ds] $Dbs[$ds] $sql
}
$suffix = Get-Date -Format "yyyyMMddHHmmss"
$tokens = @()
foreach ($i in 0..39) {
    $name = "p3$suffix$($i.ToString('00'))"
    $tokens += , (Register $name "P3pass123")
}
$i = 0
foreach ($tk in $tokens) {
    $null = New-Order $tk
    $i++
    if ($i % 10 -eq 0) { Write-Host "    已下单 $i/40" }
}
$total = 0
$minCount = [int]::MaxValue
foreach ($ds in 0..1) {
    $counts = Get-ShardCounts $ds "oms_order"
    foreach ($t in 0..3) {
        Write-Host "    $($Dbs[$ds]).oms_order_$t = $($counts[$t])"
        $total += $counts[$t]
        if ($counts[$t] -lt $minCount) { $minCount = $counts[$t] }
    }
}
Assert-True ($total -eq 40) "订单总数不是 40（实际 $total）"
Assert-True ($minCount -eq 5) "分片分布不均衡（单分片最少 $minCount 条，期望 5 条）"
Write-Host "    总计 $total 条，单分片最少 $minCount 条（8 分片偏差 0%）"

Write-Host ""
Write-Host "P3_OK 订单状态流转 / 单分片路由 / 8 分片广播 / 分片均衡 全部通过" -ForegroundColor Green
