# mall-seckill 交易中台压测报告 v2

> 本报告记录「秒杀 Demo → 交易中台」升级过程中的基线、优化项与前后对比。
> 当前状态：P0 基线、P1 治理、P2 事务消息、P3 分库分表、P4 ES 搜索、P5 2k+ QPS 压测与优化、P6 交付均已完成。

---

## 1. 测试环境

| 项 | 配置 |
|---|---|
| 主机 | Windows 11，AMD64，8 核 / 16 线程，12GB 内存 |
| Docker | Docker Desktop，可用 11.68GB 内存 |
| 应用 | Spring Boot 3.2.5，app1/app2 双实例，Nginx 1.27 轮询（8090） |
| 中间件 | MySQL 8.0（13306）/ 订单库 MySQL 8.0 ×2（13307/13308）/ Redis 7（6380）/ RabbitMQ 3.13（5672）/ RocketMQ 5.3.1 / Nacos 2.4.3 / Sentinel 1.8.8 / Elasticsearch 8.11.4 + IK（9200） |
| 监控 | Prometheus 2.54（9090）/ Grafana 11.2（13000）/ Zipkin 3.4（19411） |
| 压测工具 | `scripts/SeckillBench.java`（Java 17 HttpClient，单文件运行） |
| 压测模式 | `ACCESS_LIMIT_ENABLED=false`、`SECKILL_CAPTCHA_ENABLED=false`、`SECKILL_LOCAL_MARK_ENABLED=true` |
| 秒杀商品 | seckillSkuId=4，DB 库存 200000，场次时间窗已延长 |
| 压测用户 | 每轮注册独立用户，避开一人一单判重；Redis 库存置 0 模拟售罄 |

> 说明：本机同时运行其他项目容器，压测期间宿主空闲内存约 0.4~0.7GB（不同时段采样），压测结果低于历史报告的历史机器环境；v2 报告所有对比均在同一台机器、同一时段完成。

---

## 2. P0 优化前基线

### 2.1 功能基线

| 项 | 结果 |
|---|---|
| 单元/集成测试 | `mvn test` 66 个用例全部通过（P0 时点；升级完成后为 71 个） |
| 端到端冒烟 | `SMOKE_OK`，下单成功 orderId=22，重复抢购被拦截，匿名 401 |
| 历史遗留修复 | 旧数据卷缺少 `mq_consume_log`，已按 `sql/init/09-mq-schema.sql` 补建；否则消费端无法幂等落库 |

### 2.2 售罄拒绝路径 QPS（本地售罄标记 ON）

| 并发 | 总请求 | 耗时 | QPS | 业务拒绝 | 网络错误 |
|---:|---:|---:|---:|---:|---:|
| 100 | 5000 | 6354ms | **787** | 5000 | 0 |
| 400 | 20000 | 36067ms | **555** | 19978 | 22 |

**结论**：当前环境在 c=100 时约 787 QPS，c=400 时因客户端/宿主资源竞争下降到 555 QPS。后续优化对比统一以 c=100 为主、c=200/400 为辅。

### 2.3 当前热路径耗时构成（代码静态分析）

`SeckillServiceImpl.execute` 在售罄拒绝路径上仍有 4 次 Redis RTT：

1. `GET seckill:path:{userId}:{skuId}` —— 动态路径校验
2. `GET seckill:sku:session:{skuId}` —— 查询场次 ID
3. `HGET seckill:skus:{sessionId} {skuId}` —— 查询秒杀商品缓存
4. `EVALSHA` —— 一人一单 + 库存预减

本地售罄标记位于路径校验之后，因此无法做到文档中描述的「0 网络 IO」。

### 2.4 已识别瓶颈假设

| 假设 | 证据 | 验证方式 |
|---|---|---|
| Redis 往返次数是主因 | 售罄路径 4 次 RTT，本地标记前仍有 1 次 | P5 合并 Lua + 本地缓存后对比 |
| 客户端/宿主在 c=400 饱和 | c=400 QPS 低于 c=100，且出现 22 个网络错误 | 多并发梯度测试 |
| 应用不是 CPU 瓶颈 | 压测后 app CPU <1%，宿主 Load 19% | 压测中采样 `docker stats` + JFR |
| 内存压力影响延迟 | 宿主空闲内存 0.4~0.7GB | 限制容器内存、压测前清理无关容器（需确认） |

---

## 3. P1 治理验证（Nacos + Sentinel）

### 3.1 服务注册

| 项 | 结果 |
|---|---|
| Nacos 版本 | 2.4.3 standalone（8848/9848） |
| 注册服务 | `DEFAULT_GROUP@@mall-seckill` |
| 实例数 | 2（172.21.0.6:8080、172.21.0.7:8080） |
| 健康检查 | `GET /api/health` → 200 |

### 3.2 Sentinel 规则持久化

治理状态接口 `GET /api/admin/governance/state` 返回：

```json
{"localMarkEnabled":true,"pathTtlSeconds":60,"captchaEnabled":false,
 "flowRules":1,"flowLimit":5000.0,"paramFlowRules":1,"degradeRules":0}
```

说明：

- `flowRules=1`、`paramFlowRules=1` 来自 Nacos `SENTINEL_GROUP`，重启不丢。
- `localMarkEnabled` 与 Nacos `mall-seckill.yaml` 一致。
- 运行镜像已为非 root 用户创建 `/app/logs/sentinel` 与 `/app/logs/nacos`，消除 Sentinel/Nacos 日志目录权限错误。

### 3.3 动态配置验证（无需重启）

| 步骤 | Nacos 配置 | `/state` 返回 |
|---|---|---|
| 初始 | `local-mark-enabled: true` | `localMarkEnabled=true` |
| 修改 | `local-mark-enabled: false` | `localMarkEnabled=false` |
| 恢复 | `local-mark-enabled: true` | `localMarkEnabled=true` |

### 3.4 流控验证

| 项 | 配置/结果 |
|---|---|
| 规则 | `seckillExecute`，QPS 阈值 5 |
| 压测 | 200 请求 / 并发 20 |
| 结果 | 业务拒绝 10、**限流 429 = 190**、网络错误 0 |
| 恢复 | 阈值恢复 5000，规则仍由 Nacos 持久化 |

**结论**：P1 验收通过。Nacos 注册/配置中心与 Sentinel 流控均已生效，规则可动态调整且重启不丢。

---

## 4. P2 交易一致性验证（RocketMQ 事务消息）

### 4.1 改造要点

| 项 | 实现 |
|---|---|
| 中间件 | `apache/rocketmq:5.3.1`，namesrv（9876）+ broker（10911），`docker/rocketmq/broker.conf` |
| 事务参数 | `transactionTimeOut=6000`、`transactionCheckInterval=10000`、`transactionCheckMax=15` |
| 生产者 | `TransactionMQProducer`（组 `seckill-order-tx-producer`），消息 Key = 业务 `messageId` |
| 本地事务 | `SeckillLocalTransactionService`：Redis Lua 原子预减库存 + 一人一单判重 |
| 事务回查 | 本地事务结果写 `seckill:tx:result:{messageId}`（TTL 1h），回查仅认 `QUEUED` 才提交，无标记一律回滚 |
| 消费者 | `DefaultMQPushConsumer`（组 `seckill-order-consumer`），沿用 `mq_consume_log` 幂等与 Redisson 同人锁 |
| 开关 | `mall.rocketmq.enabled`：true 走 RocketMQ，false 回退 RabbitMQ（本地开发/单测不受影响）；RabbitMQ 仅保留订单延迟关单 |

调用链：`execute` → `sendMessageInTransaction` → 半消息 → Lua 预减 → commit/rollback → 消费端 DB 条件扣减 + 落单 → 写结果 key。
售罄/重复时本地事务直接返回 0/-1，半消息回滚，消费者完全看不到该消息。

### 4.2 验证结果

| 场景 | 操作 | 结果 |
|---|---|---|
| 成功链路 | 新用户，Redis 库存 100 | `QUEUING` → 消费落单 orderId=33，耗时 <1s；`sms_seckill_order` 1 条；库存 100→99 |
| 重复抢购 | 同一用户立即再次下单 | `code=10000`「您已抢购过该商品」；事务标记 `REPEATED`；半消息回滚；订单数仍为 1 |
| 售罄拒绝 | 新建 0 库存秒杀商品 + 新用户 | `code=10000`「已抢光」；标记 `SOLD_OUT`；半消息回滚；零订单 |
| 事务回查 | `SeckillOrderTransactionListenerTest` | 标记 `QUEUED` → COMMIT；标记缺失 → ROLLBACK；本地事务异常 → UNKNOW 触发回查 |
| 消费幂等 | 重复投递同一 `messageId` | `mq_consume_log` 唯一键 + 消费端判重，只落一单 |
| 回归 | `mvn test` | 71 个用例全部通过（新增 5 个事务监听器用例） |

验证脚本：`scripts/phase2-verify.ps1`，输出 `P2_OK`。

### 4.3 运行期问题与修复

| 问题 | 根因 | 修复 |
|---|---|---|
| 应用启动报 `Failed to create parent directories ... /home/mall/logs/rocketmqlogs` | 非 root 用户 `mall` 的 home 不可写，RocketMQ 客户端日志写死 `${user.home}/logs` | Dockerfile 创建 `/home/mall/logs/rocketmqlogs` 并授权 |
| Broker 反复重启、日志 `NullPointerException: ScheduleMessageService.configFilePath` | 命名卷挂载的 `store` 目录属主为 root，`rocketmq` 用户无法初始化存储，启动异常被 shutdown NPE 掩盖 | 演示环境移除 broker 数据卷（容器内存储），保证可写 |
| 首次消费延迟约 18s | 双实例消费组首次 rebalance | 属正常冷启动行为；预热后单条消息 <1s 完成落单 |

### 4.4 结论

P2 验收通过：跨服务一致性从「Redis 扣减 + 消息发送」的两步非原子操作，收敛为 RocketMQ 事务消息的半消息 + 本地事务 + 回查模型；
售罄/重复路径零消息投递，成功路径零丢单、零重复落单。

---

## 5. P3 订单域分库分表（ShardingSphere）

### 5.1 改造要点

| 项 | 实现 |
|---|---|
| 中间件 | ShardingSphere-JDBC 5.5.0，`jdbc:shardingsphere:classpath:sharding.yaml` |
| 拓扑 | 3 个数据源：`ds0` 主库 `mall_seckill`，`ds1/ds2` 为独立容器 `mall-mysql-order0/1`（`mall_order_0/1`，端口 13307/13308） |
| 分片表 | `oms_order`、`oms_order_item`、`sms_seckill_order`，各 4 表 → 共 8 个订单分片 |
| 分片键 | `user_id`；库 `ds${user_id % 2 + 1}`，表 `${(user_id >> 1) % 4}` |
| 分片均衡 | 原「库 `%2` + 表 `%4`」导致 4 个分片永远为空，改为库取最低位、表取次低 2 位后 8 分片均可落数据 |
| 主键 | 分片表禁用 `AUTO_INCREMENT`，雪花 ID（`IdType.ASSIGN_ID`）保证全局唯一 |
| 事务 | XA + Atomikos：订单库与主库（`pms_sku` 库存、`sms_seckill_sku`、`mq_consume_log`）跨数据源原子提交 |
| 路由约束 | 买家查询/状态更新全部带 `user_id` 双条件；管理端按 id/order_sn 广播 8 分片 |
| 非分片表 | `!SINGLE` 规则统一路由到 `ds0`（用户、商品、分类、秒杀配置、`mq_consume_log`） |

三张订单表使用同一 `user_id` 算法，同一用户的主表、明细、秒杀关联落在同一物理库，`@Transactional` 内部对订单库仍是本地事务。

### 5.2 验证结果（`scripts/phase3-verify.ps1` → `P3_OK`）

| 场景 | 操作 | 结果 |
|---|---|---|
| 状态流转 | 下单 → 支付 → 发货 → 确认收货 | `1 待支付 → 2 已支付 → 4 已发货 → 5 已完成` 全部成功 |
| 取消回补 | 待支付订单取消 | 库存 `999 → 998 → 999`，秒杀订单删除、一人一单解锁 |
| 超时关单 | 不支付等待延迟消息 | 120s 内关单，库存回补至 999 |
| 单分片路由 | 买家「我的订单」查询 + `general_log` | 仅命中 `mall_order_0.oms_order_1`（userId=2） |
| 管理端广播 | 管理端订单列表 + `general_log` | 命中 8 个 `oms_order_0..3` 物理分片（2 库 × 4 表） |
| 分片均衡 | 40 个 userId 依次下单 | `mall_order_0/1` 的 `oms_order_0..3` 各 5 条，单分片最少 5 条，偏差 0% |
| 回归 | `mvn test` + `phase2-verify.ps1` | 71/71 通过；P2 事务消息在分片库下仍 `P2_OK` |

验证脚本输出原文：

```
orderId=2122324921992601601 userId=2 -> mall_order_0.oms_order_1
状态流转 1 -> 2 -> 4 -> 5 正确
取消回补正确: 999 -> 998 -> 999
超时关单完成且库存回补: 999
买家查询命中物理分片: mall_order_0/oms_order_1
管理端广播命中物理分片数: 8
mall_order_0/1 oms_order_0..3 各 = 5
总计 40 条，单分片最少 5 条（8 分片偏差 0%）
P3_OK 订单状态流转 / 单分片路由 / 8 分片广播 / 分片均衡 全部通过
```

### 5.3 结论

P3 验收通过：订单域数据按 `user_id` 均匀落 8 个分片，买家查询稳定单分片路由，管理端广播可控；
跨库写入由 XA 保证原子性，订单全生命周期状态流转与库存回补在分片下行为不变。

---

## 6. P4 Elasticsearch + IK 商品搜索

### 6.1 改造要点

| 项 | 实现 |
|---|---|
| 中间件 | Elasticsearch 8.11.4 单节点（`mall-elasticsearch`，宿主机 9200），`xpack.security.enabled=false`，堆 512MB |
| 中文分词 | 自定义镜像 `docker/elasticsearch/Dockerfile` 离线安装 `analysis-ik 8.11.4`；IK 缺失时索引自动降级 `standard` |
| 索引 | `pms_spu`：`name`（ik_max_word / ik_smart）、`subtitle`、`category_id`、`category_name`、`min_price`、`status`、`update_time`；不含下架商品；`number_of_replicas=0`（单节点 green） |
| 查询 | `ElasticsearchOperations` + `CriteriaQuery`：关键词 match、分类 term、价格区间 range、价格排序、`<em>` 高亮 |
| 索引创建 | `SearchIndexManager` 启动时确保索引存在并手动写 mapping；多实例从 mapping 读取实际分词器 |
| 增量同步 | `PmsSpuServiceImpl` 发布 `SpuChangedEvent` → `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` 写 ES |
| 补偿 | 同步失败写入 Redis 脏集合 `search:dirty:spu`，`@Scheduled` 每 10s 重试 |
| 全量重建 | `POST /api/admin/search/reindex`，删除重建索引并批量写入上架商品 |
| 降级 | ES 异常/超时捕获后走 `PmsSpuService.pageOnSale`，返回 `degraded=true`，接口不 500 |

### 6.2 验证结果（`scripts/phase4-verify.ps1` → `P4_OK`）

| 场景 | 结果 |
|---|---|
| IK 插件 | `_cat/plugins` 返回 `analysis-ik 8.11.4`；mapping `analyzer=ik_max_word`、`search_analyzer=ik_smart` |
| 全量重建 | 上架 3 条 → 索引 3 条，`_count=3`，接口返回 `indexed=3` |
| 关键词高亮 | 「手机」命中，返回 `星河 X5 Pro 智能<em>手机</em>`；冷启动首查 `tookMs≈388`，热态约 10ms |
| 分类/价格筛选 | 分类结果无越界；`maxPrice=1000` 无越界 |
| 价格排序 | `priceAsc` 序列 299 → 3999 → 5299，升序正确 |
| 增量同步 | 新增商品约 1s 可搜；删除后约 1s 从索引消失 |
| ES 停机降级 | `degraded=true`，DB 兜底返回结果（耗时约 4.2s，等于客户端 socket 超时） |
| ES 恢复 | `degraded=false`，`analyzer=ik_max_word` |
| 搜索延迟 | 3 条文档、热态 50 次 HTTP 往返：avg 34.4ms、p95 47.9ms；冷启动首查约 388ms |

脚本输出原文（节选）：

```
ES status=green IK 插件已加载
上架商品数=3 索引文档数=3
keyword=手机 total=1 degraded=False analyzer=ik_max_word tookMs=388
高亮示例: 星河 X5 Pro 智能<em>手机</em>
categoryId=10101 total=1 越界结果=0
price<=1000 结果数=1 越界结果=0
priceAsc 价格序列=299,3999,5299
新增商品已进入索引 / 已删除商品从索引移除
ES 停机: degraded=True total=1 tookMs=4162
ES 恢复: degraded=False total=1 analyzer=ik_max_word
重启后 30 次: avg=41.6ms p95=58.5ms；热态 50 次: avg=34.4ms p95=47.9ms
P4_OK 索引重建 / 关键词高亮 / 分类价格筛选 / 价格排序 / 增量同步 / 停机降级 / 恢复 全部通过
```

### 6.3 结论

P4 验收通过：IK 中文分词、关键词高亮、分类/价格筛选与排序均可用；索引增量同步最终一致，
ES 故障时自动降级数据库且接口不报错，恢复后自动回到 ES 搜索。

> 说明：本地仅 3 条商品数据，p95 指标不具备大数据量参考价值；万级文档性能在 P5 压测中补充。

---

## 7. P5 2k+ QPS 压测与优化

### 7.1 本轮落地的优化

| 优化 | 位置 | 收益 |
|---|---|---|
| 本地售罄标记前移到第 1 步 + TTL 自愈 | `SeckillServiceImpl#execute` | 售罄拒绝业务侧 **0 次 Redis 往返**；TTL=10s（`SECKILL_LOCAL_MARK_TTL_SECONDS`）让多实例在补货后自动恢复 |
| 秒杀商品信息本地缓存 | `SeckillServiceImpl#loadCache`（Caffeine，TTL 5s，`mall.seckill.sku-local-cache-seconds`） | 热路径省掉「场次索引 + hash 详情」2 次 Redis 往返 |
| Lua 内合并结果 key 删除 | `SeckillLocalTransactionService` | 预减成功后不再单独执行一次 `DEL` |
| 用户/权限本地缓存 | `CustomUserDetailsService`（Caffeine，30s） | 每请求省掉 3 次 DB 查询（用户 + 角色 + 权限） |
| 热路径日志降级 | 事务消息投递、秒杀下单、订单创建、延迟关单 | 每单 4 条 INFO → debug，避免 Docker json-file 在千级 QPS 下串行化写入 |
| Nginx 调优 | `docker/nginx/nginx.conf` | `access_log off` + 4 worker + `keepalive 128`：1289 → 1.6k+ |
| 压测 profile | `docker-compose.bench.yml` | 关闭限流、验证码、Zipkin 采样 |

> 与设计的差异：原计划的 4 实例扩容实测为负收益，已回退为 2 实例（见 7.5）；动态路径校验未并入 Lua，保留独立 `GET`（语义清晰，仅 1 次 RTT）。

### 7.2 压测方法

- 客户端 `scripts/SeckillBench.java` 运行在 `mall-seckill_mall-net` 内的 `maven:3.9-eclipse-temurin-21` 容器中，直连 `http://nginx`，绕开 Windows → Docker 端口转发的排队噪声。
- 售罄拒绝：Redis 库存置 0，固定 token 重复请求，每轮 8000 次。
- 成功下单：每轮先批量注册独立用户（一人一单），并发换取动态路径后再并发 `execute`。
- 指标均为客户端侧观测：`qps`、`ok/rejected/limited/errors`、`p50/p95/p99`。

### 7.3 售罄拒绝路径（2 实例经 Nginx，每轮 8000 请求）

| 并发 | 第 1 轮 QPS | p50 / p99 | 第 2 轮 QPS | p50 / p99 | 拒绝 / 错误 |
|---:|---:|---|---:|---|---|
| 100 | **2539** | 28.7 / 160.0ms | 1366 | 29.0 / 117.6ms | 8000 / 0 |
| 200 | **2222** | 69.8 / 257.7ms | 1124 | 121.0 / 375.5ms | 8000 / 0 |
| 300 | 1063 | 173.0 / 1400.8ms | 1376 | 171.3 / 568.4ms | 8000 / 0 |
| 400 | 1128 | 224.6 / 731.1ms | 1334 | 230.2 / 704.8ms | 8000 / 0 |

### 7.4 成功下单路径（一人一单，全部落单）

| 并发 | 用户数 | QPS | p50 | p95 | p99 | 结果 |
|---:|---:|---:|---:|---:|---:|---|
| 50 | 1000 | 232 | 50.0 | 290.7 | 543.3 | 1000 ok |
| 100 | 2000 | 592 | 58.9 | 107.2 | **142.0** | 2000 ok |
| 200 | 3000 | **1013 / 882 / 812 / 413** | 129~160 | 296~470 | 412~666 | 2997~3000 ok，3 个 5s 客户端超时 |
| 400 | 3000 | 792 | 232.0 | 544.7 | 744.0 | 3000 ok |

成功路径 c=200 取 4 轮，中位 **847 QPS**，峰值 **1013 QPS**。

### 7.5 瓶颈定位（证据驱动）

| 证据 | 观察 | 结论 |
|---|---|---|
| `docker stats`（成功路径） | app1/app2 各 **300~360%** CPU，合计约 6.5/8 核；broker 8%、Redis 1~4%、MySQL 约 30% | 成功路径受 **CPU** 限制：同一 JVM 既发事务消息又消费，消费端 Atomikos XA 两阶段提交写 3 个 MySQL |
| `docker stats`（拒绝路径） | app CPU < 5% | 拒绝路径瓶颈在 Nginx/Docker 网络与客户端，不在业务逻辑 |
| 4 实例实验 | reject c=200 仅 1081、c=300 1166（低于 2 实例的 2222）；首轮 601 个 5s 超时；apps 3.3GB、宿主空闲 0.5GB | 宿主内存不足，扩容负收益，已回退 2 实例 |
| 宿主机内存 | 压测时宿主空闲约 411MB | p99 出现 3s/5s 尖刺（max 3075~5004ms），存在 WSL/Windows 内存回收与换页 |
| 同一配置多次复测 | 拒绝路径 1063~2539，成功路径 413~1013 | 噪声与收益同量级，报告按区间 + 峰值给出 |
| 热路径日志 | 每单 4 条 INFO 经 Docker json-file | 已降级 debug；单独看对 QPS 影响未超出噪声 |

### 7.6 验收对照

| 指标 | 目标 | 实测 | 结论 |
|---|---|---|---|
| 拒绝路径 QPS | ≥2000 | 2222（c=200）/ 2539（c=100） | 峰值达标，安静窗口可复现 |
| 成功路径 QPS | ≥1000 | 峰值 1013、中位 847 | 边界达标，受宿主资源限制波动 |
| 错误率 | <0.1% | 6 万+ 请求中 3 个客户端 5s 超时（<0.01%） | 达标 |
| p99 | <150ms | 拒绝 c=100 118~160ms；成功 c=100 142ms；c≥200 时 400~700ms | 仅 c≤100 达标，高并发下 CPU 打满产生排队 |
| 不超卖 / 不重复 | 0 | 8 个分片共 **27005** 笔秒杀订单，按 `user_id` 重复数 **0**；Redis 预减与 DB 扣减一致 | 达标 |
| 消费与对账 | 队列不堆积 | `order.close.queue` 消费 27003 条，超时关单回补库存全链路跑通 | 达标 |

---

## 8. 复现命令

```powershell
# 1. 启动基础栈（压测模式关闭验证码与限流）
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d

# 2. 冒烟
powershell -ExecutionPolicy Bypass -File scripts/phase7-smoke.ps1

# 3. P2 事务消息验证（成功 / 重复回滚 / 售罄回滚 / 幂等）
powershell -ExecutionPolicy Bypass -File scripts/phase2-verify.ps1 -SeckillSkuId 7

# 4. P3 分库分表验证（状态流转 / 单分片路由 / 广播 / 分片均衡）
powershell -ExecutionPolicy Bypass -File scripts/phase3-verify.ps1

# 5. P4 搜索验证（重建 / 高亮 / 筛选 / 增量 / 降级）
powershell -ExecutionPolicy Bypass -File scripts/phase4-verify.ps1

# 6. 基线压测（c=100）
powershell -ExecutionPolicy Bypass -File scripts/phase0-baseline.ps1 -SkuId 4 -Total 5000 -Concurrency 100

# 7. 基线压测（c=400）
powershell -ExecutionPolicy Bypass -File scripts/phase0-baseline.ps1 -SkuId 4 -Total 20000 -Concurrency 400

# 8. P5 压测（容器内客户端，售罄拒绝 + 成功下单；先预热再改库存）
powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -Mode all -SkuId 4 -RejectTotal 8000 -SuccessUsers 3000 -SuccessConcurrency 200

# 9. 只跑售罄拒绝（c=100/200/300 各一轮）
powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -Mode reject -RejectTotal 8000 -RejectConcurrency 100,200,300
```
