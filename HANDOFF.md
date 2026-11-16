# 项目交接文档 HANDOFF

> 最后更新：2026-11-17
> 用途：供下次会话快速接手，无需重新摸索。本文档只记录事实、当前状态、坑与下一步。

---

## 1. 项目一句话

mall-seckill：个人独立完成的前后端分离高并发电商秒杀 / 交易中台，业务闭环完整（RBAC → 商品 → 购物车 → 订单 → 秒杀 → 搜索），核心是「六级流量漏斗 + Redis Lua 原子预减 + RocketMQ 事务消息异步下单 + 订单域分库分表」。

- 远程仓库：https://github.com/Gunaie/mall-seckill（`main` 分支）
- 本地路径：`e:\Trae\mall-seckill`
- 技术栈：Spring Boot 3.2.5 / Java 17 / MyBatis-Plus 3.5.7 / Druid 1.2.23 / MySQL 8 / Redis 7 / Redisson 3.31 / Caffeine / RabbitMQ 3.13 / RocketMQ 5.3.1 / ShardingSphere-JDBC 5.5.0（XA + Atomikos）/ Nacos 2.4.3 / Sentinel 1.8.8 / Elasticsearch 8.11.4 + IK / Spring Security 6 + JWT / Nginx / Docker Compose / Prometheus + Grafana / Micrometer Tracing + Zipkin / Vue 3 + Vite + Pinia

## 2. 当前状态快照（接手先看这里）

### 阶段进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 环境重建与基线（本地标记关闭时的原始基线） | ✅ |
| P1 | Nacos 注册/配置中心 + Sentinel 热点限流（规则持久化） | ✅ |
| P2 | RocketMQ 事务消息替换异步下单链路（半消息/本地事务/回查） | ✅ |
| P3 | ShardingSphere 订单域 8 分片 + XA（含 P2 回归） | ✅ |
| P4 | Elasticsearch 8.11.4 + IK 商品搜索（含 UI 验证） | ✅ |
| P5 | 2k+ QPS 压测与热路径优化 | ✅ |
| P6 | 文档与交付（README / HANDOFF / 面试问答 / 提交） | ✅ |

- 测试：`mvn test` 全绿（10 个测试类 / 71 个用例：67 单元 + 4 集成）
- 可运行性：`docker compose up -d` 一键起全栈（16 个容器），入口 http://localhost:8090
- 默认账号：admin/admin123；压测账号 p5_reject/123456

### 本轮升级新增容器

| 容器 | 端口 | 说明 |
|---|---|---|
| mall-mysql-order0 / order1 | 13307 / 13308 | 订单分库 ds1 / ds2 |
| mall-rocketmq-namesrv / broker | 9876 / 10911 | 事务消息 |
| mall-nacos | 8848（gRPC 9848） | 注册 + 配置 + Sentinel 规则存储 |
| mall-sentinel | 8858 | 控制台（sentinel/sentinel） |
| mall-elasticsearch | 9200 | ES 8.11.4 + IK，索引 `pms_spu` |

## 3. 阶段成果速记

### P1 治理

- 双实例注册到 Nacos（`mall-seckill-app`），配置项与 Sentinel 规则存 Nacos
- Sentinel 热点参数限流：`seckill:execute` 2000 QPS/实例，`/api/admin/governance/state` 可查看当前规则
- 验证脚本 `scripts/phase1-verify.ps1`（输出 `GOVERNANCE_VERIFY_DONE`）

### P2 事务消息

```
sendMessageInTransaction
  → ① 半消息（消费者不可见）
  → ② executeLocalTransaction：Redis Lua 预减 + 一人一单，结果写 Redis 事务标记（TTL 1h）
  → ③ COMMIT / ROLLBACK
  → ④ Broker 回查 checkLocalTransaction（读 Redis 标记）
```

- 三类本地事务：成功 COMMIT、重复下单 ROLLBACK、售罄 ROLLBACK
- 消费端 `mq_consume_log` 幂等不变；`mall.rocketmq.enabled=false` 可回退 RabbitMQ + afterCommit 链路
- 验证脚本 `scripts/phase2-verify.ps1`

### P3 分库分表

- `src/main/resources/sharding.yaml`：ds1/ds2 两个订单库，`oms_order` / `oms_order_item` / `sms_seckill_order` 各 4 表 = 8 分片
- 路由：库 `user_id % 2`，表 `(user_id >> 1) % 4`；事务 XA + Atomikos（`jta` 事务管理器）
- 验证脚本 `scripts/phase3-verify.ps1`（分片分布、同槽位路由、状态流转）

### P4 搜索

- `src/main/java/com/mall/search/`：`ProductSearchController`（`GET /api/search/spu`、`POST /api/admin/search/reindex`）
- 增量同步：Redis 脏集合 + 定时补偿；ES 不可用自动降级 DB 分页
- 验证脚本 `scripts/phase4-verify.ps1`（输出 `P4_OK`）

### P5 压测与优化

热路径减负（详见 `benchmark-report-v2.md` 第 7 节）：

| 优化 | 收益 |
|---|---|
| 本地售罄标记前移到第 1 步 + TTL 10s 自愈 | 拒绝路径业务侧 0 次 Redis 往返 |
| 秒杀商品信息 Caffeine 5s（`mall.seckill.sku-local-cache-seconds`） | 省 2 次 Redis 往返 |
| 认证信息 Caffeine 30s（`CustomUserDetailsService`） | 省每请求 3 次 DB 查询 |
| Lua 预减合并删除结果 key | 省 1 次 DEL |
| 每单 INFO 日志 → debug | 降低 Docker json-file 写入压力 |
| Nginx `access_log off` + 4 worker + keepalive 128 | 1289 → 1.6k+ |

实测（2 实例经 Nginx，压测客户端跑在 compose 网络内）：

| 场景 | 结果 |
|---|---|
| 售罄拒绝 | c=100 2539、c=200 2222、c=300 1063~1376、c=400 1128~1334 QPS，0 错误 |
| 成功下单（一人一单） | c=200 峰值 1013 / 中位 847，c=400 792，c=100 p99 142ms |
| 一致性 | 8 分片累计 27005 单，按 `user_id` 重复 0，超时关单回补库存跑通 |
| 错误率 | 6 万+ 请求中 3 个客户端 5s 超时（<0.01%） |

## 4. 核心架构速记

```
本地售罄标记(0 RTT) → 动态路径 GET → 商品/时间窗(本地缓存)
  → Redis Lua 预减 + 一人一单
  → RocketMQ 事务消息（半消息 / 本地事务 / COMMIT-ROLLBACK / 回查）
  → 消费端: mq_consume_log 幂等 + Redisson 锁 + DB 条件扣减 + 三表落库（XA 8 分片）
  → afterCommit 写 result key，前端轮询 QUEUING/SUCCESS/FAIL
```

两条 MQ 链路并存：

- 超时关单：RabbitMQ `order.delay.queue`（TTL 60s）→ `order.close.queue` → CAS 关单 + 回补（同时删除 `sms_seckill_order` 关系记录）
- 秒杀下单：RocketMQ 事务消息 → `seckill.order.queue`（`mall.rocketmq.enabled=false` 时回退 RabbitMQ + DLQ）

## 5. 常用命令

```bash
# 全栈启动 / 停止
docker compose up -d
docker compose down

# 压测模式（关闭限流/验证码/Zipkin 采样）
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d

# 测试
mvn test

# P0~P5 阶段验证
powershell -ExecutionPolicy Bypass -File scripts/phase1-verify.ps1
powershell -ExecutionPolicy Bypass -File scripts/phase2-verify.ps1
powershell -ExecutionPolicy Bypass -File scripts/phase3-verify.ps1
powershell -ExecutionPolicy Bypass -File scripts/phase4-verify.ps1
powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -Mode all -RejectTotal 8000 -SuccessUsers 3000 -SuccessConcurrency 200

# Nginx 配置改动后（bind mount，改宿主文件即生效）
docker exec mall-nginx nginx -t; docker exec mall-nginx nginx -s reload

# RabbitMQ 队列参数变更后需删除旧队列
docker exec -it mall-rabbitmq rabbitmqctl delete_queue seckill.order.queue
```

## 6. 已知薄弱点 / 风险（面试主动承认）

1. **afterCommit 极小窗口丢消息**：`mall.rocketmq.enabled=false` 回退链路下存在「DB 已提交、MQ 未发、进程宕机」窗口；事务消息链路已消除该问题，但两种链路并存需说明。
2. **本地状态不共享**：Caffeine 与售罄标记是实例级，靠短 TTL 收敛（售罄标记 10s、秒杀信息 5s、认证 30s）。补货后需对每台实例触发预热或等 TTL。
3. **成功路径 CPU 瓶颈**：同一 JVM 既发事务消息又消费，Atomikos XA 写 3 个 MySQL，P5 实测 app1/app2 各 300~360% CPU。继续提升需拆分消费进程或减少 XA 参与方。
4. **宿主资源**：8 核 / Docker VM 11.68GB，压测时空闲内存约 0.4~0.7GB，p99 出现 3~5s 尖刺；4 实例扩容反而降至 1081 QPS，已回退 2 实例。
5. **ES 单节点**：无副本、无鉴权，仅演示用；索引重建依赖定时补偿。
6. 压测数字均为本地单机数据，不可宣称为生产数据。

## 7. 下一步计划

- [x] P6 收尾：README / HANDOFF / `docs/07-交易中台面试问答.md` / 提交
- [ ] Outbox 本地消息表 + 定时对账（补齐回退链路的丢消息窗口）
- [ ] 消费进程拆分（或独立 consumer 实例），验证成功路径能否稳定 ≥1000 QPS 且 p99 < 150ms
- [ ] Zipkin 接 Elasticsearch 持久化；ES 增加副本与鉴权

## 8. 技术陷阱备忘（勿重踩）

- **Lua 必须用 StringRedisTemplate**：JSON 序列化模板会给参数加引号导致 `tonumber` 失败。
- **本地售罄标记必须带 TTL**：多实例补货只能清理处理该请求的实例，TTL 让其余实例自愈；压测脚本连续 4 次 preheat + 等 11s。
- **`docker cp` 覆盖 nginx.conf 会报 device or resource busy**：它是 bind mount，直接改宿主文件再 `nginx -s reload`。
- **Nginx `access_log` 写 stdout 在千级 QPS 下被 Docker json-file 串行化**：压测 profile 下必须关闭。
- **Docker `-v "$env:TEMP:/tokens"` 陷阱**：PowerShell 变量末尾带 `\` 会被解析成 `C:\...\Temp\:/tokens`，变成匿名卷；必须写 `"${env:TEMP}:/tokens"` 或字面路径。
- **PowerShell `curl` 传 JSON 会被吞引号**：用 `--data-binary "@临时文件"`（UTF-8 无 BOM）或单引号字面量。
- **Prometheus 只有 summary 没有 histogram**：`http_server_requests_seconds_bucket` 不存在，p99 用客户端侧数据。
- **Testcontainers 与 Docker Desktop 29 不兼容**：Redis 集成测试用 `com.github.codemonstur:embedded-redis`。
- **RabbitMQ 队列声明参数变更后必须删旧队列**。
- **取消/超时关单必须删除 `sms_seckill_order` 关系记录**，否则 `uk_user_sku` 阻止复购（`restoreSeckillStockAndCleanup` 已处理，会同时回补 DB 与 Redis 库存）。
- **`docker kill -s QUIT` 只发给 PID 1（sh）**：要抓 JVM 线程栈需 `docker exec` 找到 java 进程再 `kill -3`。
- Compose 项目名固定 `mall-seckill`（网络 `mall-seckill_mall-net`）。
- Windows：命令分隔用 `;`；git 长提交信息用多段 `-m`；git 命令需非沙箱执行。

## 9. 关键文件索引

| 用途 | 路径 |
|---|---|
| 秒杀服务（漏斗 / Lua / 消费 / 补偿 / 预热 / 本地缓存） | src/main/java/com/mall/service/impl/SeckillServiceImpl.java |
| 秒杀本地事务与回查 | service/SeckillLocalTransactionService.java、mq/rocketmq/SeckillOrderTransactionListener.java |
| RocketMQ 配置与投递 | config/RocketMqProperties.java、mq/rocketmq/RocketMqConfig.java、RocketSeckillOrderSender.java |
| RocketMQ 消费者 | mq/rocketmq/SeckillOrderRocketConsumer.java |
| 普通订单服务（含关单回补） | service/impl/OrderServiceImpl.java |
| 分片规则 | src/main/resources/sharding.yaml、sql/sharding/order-shard-schema.sql |
| 搜索 | search/ProductSearchController.java、search/SpuSearchService.java |
| 认证与用户缓存 | security/CustomUserDetailsService.java、controller/AuthController.java |
| 热路径配置项 | config/SeckillProperties.java、application-prod.yml |
| Nginx / 压测 profile | docker/nginx/nginx.conf、docker-compose.bench.yml |
| 压测 | scripts/SeckillBench.java、scripts/phase5-bench.ps1、benchmark-report-v2.md |
| 升级方案 | docs/06-交易中台升级方案.md |
| 面试文档 | docs/01–05、docs/07-交易中台面试问答.md |
