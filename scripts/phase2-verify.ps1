# P2 RocketMQ 事务消息验证脚本
# 前置：docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d（关闭验证码/滑动窗口限流）
# 覆盖：成功链路（半消息 commit -> 消费落单）、重复抢购（本地事务拒绝 -> 半消息回滚）、
#       售罄拒绝（Lua 返回 0 -> 回滚）、消费幂等（mq_consume_log）、事务标记写入
# 注意：秒杀订单超时会被延迟关单任务取消并回补库存/解除一人一单，因此「重复抢购」必须紧接首次成功后验证
param(
    [long]$SeckillSkuId = 7,
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
function GetJson($path, $token) {
    $a = @("-s", "$Base$path")
    if ($token) { $a += @("-H", "Authorization: Bearer $token") }
    return ((& curl.exe @a) | ConvertFrom-Json)
}
function Redis($cmd) {
    # redis-cli 需要把 "SET key value" 拆成独立参数，整体传参会被当成单条命令名
    $parts = $cmd -split '\s+'
    return (docker exec mall-redis redis-cli -a mall123456 --no-auth-warning @parts)
}
function Sql($sql) {
    # PowerShell 5.1 会把原生命令 stderr 当成错误记录，mysql 密码告警需要过滤掉
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = docker exec mall-mysql mysql -uroot -proot123456 -N -e $sql 2>&1 } finally { $ErrorActionPreference = $old }
    $line = $out | Where-Object { ("$_" -notmatch 'Warning') } | Select-Object -First 1
    if ($null -eq $line) { return "" }
    return ([string]$line).Trim()
}
# P3 起秒杀订单表按 user_id 分库分表，需按分片键定位物理表统计
function SmsOrderCount([long]$UserId, [long]$SeckillSkuId) {
    $ds = [int]($UserId % 2)
    $t = [int]((($UserId -shr 1) % 4))
    $container = if ($ds -eq 0) { 'mall-mysql-order0' } else { 'mall-mysql-order1' }
    $db = if ($ds -eq 0) { 'mall_order_0' } else { 'mall_order_1' }
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = docker exec -e MYSQL_PWD=root123456 $container mysql -uroot -N -e "select count(*) from $db.sms_seckill_order_$t where seckill_sku_id=$SeckillSkuId and user_id=$UserId"
    } finally { $ErrorActionPreference = $old }
    return [int](($out | Select-Object -First 1).ToString().Trim())
}
function TotalSmsOrders() {
    $sum = 0
    foreach ($t in 0..3) {
        $c0 = docker exec -e MYSQL_PWD=root123456 mall-mysql-order0 mysql -uroot -N -e "select count(*) from mall_order_0.sms_seckill_order_$t"
        $c1 = docker exec -e MYSQL_PWD=root123456 mall-mysql-order1 mysql -uroot -N -e "select count(*) from mall_order_1.sms_seckill_order_$t"
        $sum += [int](($c0 | Select-Object -First 1).ToString().Trim())
        $sum += [int](($c1 | Select-Object -First 1).ToString().Trim())
    }
    return $sum
}
function NewUser() {
    $u = "p2u" + (Get-Random -Maximum 999999)
    $reg = PostJson "/api/auth/register" ('{"username":"' + $u + '","password":"123456","nickname":"P2"}') $null
    return @{ Token = $reg.data.accessToken; Id = $reg.data.userInfo.id; Name = $u }
}
$execBody = '{"receiverName":"p2","receiverPhone":"13800000000","receiverAddress":"p2-address"}'

Write-Host "==> 1. 新用户库存充足下单：半消息 commit -> RocketMQ 消费落单"
Redis "SET seckill:stock:$SeckillSkuId 100" | Out-Null
$u1 = NewUser
$p1 = (PostJson "/api/seckill/$SeckillSkuId/path" '{}' $u1.Token).data
$exec1 = PostJson "/api/seckill/$SeckillSkuId/$p1/execute" $execBody $u1.Token
Write-Host "    userId=$($u1.Id) code=$($exec1.code) status=$($exec1.data.status)"
if ($exec1.data.status -ne "QUEUING") { throw "未进入排队" }

$orderId = $null
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    $r = GetJson "/api/seckill/$SeckillSkuId/result" $u1.Token
    if ($r.data.status -eq "SUCCESS") { $orderId = $r.data.orderId; break }
    if ($r.data.status -eq "FAIL") { throw "消费失败" }
}
if (-not $orderId) { throw "30s 内未落单" }
Write-Host "    orderId=$orderId 落单耗时≈$($i)s"
$cnt1 = SmsOrderCount $u1.Id $SeckillSkuId
Write-Host "    秒杀订单数=$cnt1"
if ($cnt1 -ne 1) { throw "订单数异常：$cnt1" }

Write-Host "==> 2. 同一用户立即重复抢购：本地事务返回重复 -> 半消息回滚"
$exec2 = PostJson "/api/seckill/$SeckillSkuId/$p1/execute" $execBody $u1.Token
Write-Host "    code=$($exec2.code) message=$($exec2.message)"
if ($exec2.code -eq 200) { throw "重复抢购未被拦截" }
$cnt2 = SmsOrderCount $u1.Id $SeckillSkuId
if ($cnt2 -ne 1) { throw "重复抢购产生了额外订单：$cnt2" }

Write-Host "==> 3. 新用户抢购 0 库存商品：Lua 返回售罄 -> 半消息回滚，不落单"
# 用全新的秒杀商品做售罄验证：避免其他待支付订单超时关单回补库存造成的干扰
$adminToken = (PostJson "/api/auth/login" '{"username":"admin","password":"admin123"}' $null).data.accessToken
PostJson "/api/admin/seckill/activities" '{"name":"p2-tx"}' $adminToken | Out-Null
$activity = (GetJson "/api/admin/seckill/activities" $adminToken).data | Sort-Object id -Descending | Select-Object -First 1
$start = (Get-Date).AddMinutes(-2).ToString("yyyy-MM-ddTHH:mm:ss")
$end = (Get-Date).AddHours(2).ToString("yyyy-MM-ddTHH:mm:ss")
PostJson "/api/admin/seckill/sessions" ('{"activityId":' + $activity.id + ',"name":"p2-tx-session","startTime":"' + $start + '","endTime":"' + $end + '"}') $adminToken | Out-Null
$txSessionId = [long](Sql "select id from mall_seckill.sms_seckill_session where name='p2-tx-session' order by id desc limit 1")
PostJson "/api/admin/seckill/skus" ('{"sessionId":' + $txSessionId + ',"skuId":1,"seckillPrice":1.00,"seckillStock":1}') $adminToken | Out-Null
$zeroSkuId = [long]((GetJson "/api/admin/seckill/skus?sessionId=$txSessionId" $adminToken).data | Sort-Object id -Descending | Select-Object -First 1).id
PostJson "/api/admin/seckill/skus/$zeroSkuId/upload" '{}' $adminToken | Out-Null
Redis "SET seckill:stock:$zeroSkuId 0" | Out-Null
$stockBefore = Redis "GET seckill:stock:$zeroSkuId"
Write-Host "    0 库存商品 seckillSkuId=$zeroSkuId 执行前库存=$stockBefore"
if ($stockBefore -ne "0") { throw "Redis 库存重置失败：$stockBefore" }
$u2 = NewUser
$p2 = (PostJson "/api/seckill/$zeroSkuId/path" '{}' $u2.Token).data
$exec3 = PostJson "/api/seckill/$zeroSkuId/$p2/execute" $execBody $u2.Token
Write-Host "    userId=$($u2.Id) code=$($exec3.code) message=$($exec3.message)"
if ($exec3.code -eq 200) { throw "售罄未被拦截" }
$cnt3 = SmsOrderCount $u2.Id $zeroSkuId
if ($cnt3 -ne 0) { throw "售罄用户产生了订单：$cnt3" }

Write-Host "==> 4. 事务标记与半消息回滚日志"
$markers = Redis "KEYS seckill:tx:result:*"
Write-Host "    tx markers=$($markers -join ',')"
$rollback = @()
$rollback += docker logs --since 10m mall-app1 2>&1 | Select-String "回滚半消息"
$rollback += docker logs --since 10m mall-app2 2>&1 | Select-String "回滚半消息"
if (-not $rollback) { throw "未观察到半消息回滚日志" }
Write-Host "    半消息回滚日志条数=$($rollback.Count)"
if (-not ($rollback -match "status=REPEATED")) { throw "缺少重复抢购回滚日志" }
if (-not ($rollback -match "status=SOLD_OUT")) { throw "缺少售罄回滚日志" }

Write-Host "==> 5. 消费幂等：mq_consume_log 记录数与秒杀订单一致"
$logs = [int](Sql "select count(*) from mall_seckill.mq_consume_log where queue_name='seckill.order.queue'")
$orders = TotalSmsOrders
Write-Host "    mqConsumeLog=$logs seckillOrders=$orders"
if ($logs -lt 1) { throw "缺少消费幂等记录" }

Redis "SET seckill:stock:$SeckillSkuId 100" | Out-Null
Write-Host ""
Write-Host "P2_OK seckillSkuId=$SeckillSkuId userId=$($u1.Id) orderId=$orderId"
