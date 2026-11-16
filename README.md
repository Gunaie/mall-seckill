# mall-seckill 电商交易与秒杀系统

基于 Spring Boot 3 的高并发秒杀与交易中台：Redis Lua 原子预减、本地内存售罄标记、RocketMQ 事务消息异步下单、ShardingSphere 订单域分库分表、Elasticsearch + IK 商品搜索、Nacos/Sentinel 服务治理、Nginx 双实例负载均衡与 Prometheus 可观测体系。

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)
![Tests](https://img.shields.io/badge/tests-71%20passed-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

---

## 目录

- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [核心功能](#核心功能)
- [秒杀漏斗设计](#秒杀漏斗设计)
- [分布式事务与一致性设计](#分布式事务与一致性设计)
- [快速开始（Docker Compose）](#快速开始docker-compose)
- [本地开发](#本地开发)
- [API 概览](#api-概览)
- [测试](#测试)
- [性能压测](#性能压测)
- [监控与可观测性](#监控与可观测性)
- [CI/CD](#cicd)
- [项目结构](#项目结构)
- [默认账号](#默认账号)

---

## 系统架构

```
                        ┌──────────────────┐
      Client  ────────▶ │  Nginx (8090)    │  轮询负载均衡 + keepalive
                        └────────┬─────────┘
                   ┌─────────────┴─────────────┐
                   ▼                           ▼
          ┌─────────────────┐         ┌─────────────────┐
          │ app1 (Spring)   │         │ app2 (Spring)   │  ← Nacos 注册/配置
          │ 本地售罄标记    │         │ 本地售罄标记    │  ← Sentinel 规则
          └──┬────┬────┬────┘         └──┬────┬────┬────┘
             │    │    │                 │    │    │
             ▼    ▼    ▼                 ▼    ▼    ▼
      ┌────────┐ ┌───────────┐   ┌─────────────┐ ┌──────────────────┐
      │ Redis7 │ │ RocketMQ  │   │ RabbitMQ    │ │ ShardingSphere   │
      │ Lua预减│ │ 事务消息  │   │ 延迟关单    │ │ JDBC (XA)        │
      └────────┘ └─────┬─────┘   └──────┬──────┘ └────────┬─────────┘
                       │                │                 │
                  ┌────▼────────────────▼─────────────────▼────┐
                  │  MySQL 8：ds0 主库（用户/商品/秒杀/幂等日志） │
                  │           ds1/ds2 订单库（oms/seckill 8 分片）│
                  └────────────────────────────────────────────┘

      Elasticsearch 8.11 + IK ── 商品搜索 / 索引增量同步
      Prometheus + Grafana + Zipkin ── 指标、大盘、链路追踪
```

## 技术栈

| 层级 | 技术 |
|---|---|
| 基础框架 | Spring Boot 3.2.5 / Java 17 |
| ORM | MyBatis-Plus 3.5.7 + Druid 1.2.23 |
| 数据库 | MySQL 8.0（主库 + 2 个订单分库） |
| 分库分表 | ShardingSphere-JDBC 5.5.0（8 分片，XA + Atomikos） |
| 缓存/分布式锁 | Redis 7 + Redisson 3.31 + Caffeine 本地缓存 |
| 消息队列 | RabbitMQ 3.13（延迟关单）+ RocketMQ 5.3.1（事务消息 + Broker 回查） |
| 注册/配置中心 | Nacos 2.4.3 |
| 流量治理 | Sentinel 1.8.8（热点参数限流，规则持久化 Nacos） |
| 商品搜索 | Elasticsearch 8.11.4 + IK 中文分词 |
| 安全 | Spring Security + JWT（access/refresh 双 token + refresh 轮换 + Redis 黑名单登出）+ BCrypt |
| 接口文档 | Knife4j 4.5（SpringDoc OpenAPI 3） |
| 监控 | Micrometer + Prometheus + Grafana |
| 链路追踪 | Micrometer Tracing + Brave（W3C traceparent） + Zipkin |
| 部署 | Docker Compose 全栈编排 + GitHub Actions CI |

## 核心功能

- **RBAC 用户体系**：用户、角色、菜单三级权限，JWT 无状态认证（refresh 轮换 + Redis 黑名单支持主动登出）
- **商品中心**：SPU/SKU 模型、分类树形结构、多级缓存（Caffeine 本地 L1 + Redis L2 + 空值防穿透 + Redisson 锁防击穿 + TTL 抖动防雪崩）
- **购物车**：Redis Hash 存储，库存校验、数量修改、勾选
- **订单系统**：状态机流转（待支付→已支付→已发货→已完成/已取消）、幂等令牌、RabbitMQ 死信队列超时自动关单、服务端重算价防篡改
- **秒杀系统**：六级漏斗（售罄标记→路径→时间→库存→一人一单→MQ）、Redis Lua 原子预减、本地内存售罄标记（P0 对比 2.16×，P5 前移后拒绝路径峰值 2539 QPS）、RocketMQ 事务消息异步下单、消费失败入死信队列(DLQ)兜底告警
- **交易一致性**：RocketMQ 事务消息（半消息 → 本地事务 → commit/rollback → Broker 回查）替代纯 afterCommit 发送，Redis 事务标记支撑回查
- **订单分库分表**：ShardingSphere-JDBC 8 分片（库 `user_id % 2`，表 `(user_id >> 1) % 4`），XA + Atomikos 保证跨库原子性，订单/秒杀关联/明细同槽位路由
- **商品搜索**：Elasticsearch 8.11.4 + IK 中文分词，关键词高亮、分类/价格筛选、销量/价格排序；Redis 脏集合增量同步 + 定时补偿，ES 不可用时自动降级 DB
- **服务治理**：Nacos 注册/配置中心，Sentinel 热点参数限流（秒杀接口 2000 QPS），规则持久化 Nacos 并在控制台热更新
- **接口限流**：基于 Redis Lua 脚本的滑动窗口限流，注解式 `@AccessLimit`
- **图形验证码**：Base64 下发，Lua 一次性消费

## 秒杀漏斗设计

六级漏斗逐级拦截，将无效请求挡在最前端，保护 DB：

```
请求 → ① 本地内存售罄标记（命中直接拒绝，0 次 Redis 调用）
     → ② 动态下单路径校验（Redis GET，防直接撞接口）
     → ③ 秒杀商品/时间窗口校验（Caffeine 本地缓存，未命中回源 Redis）
     → ④ Redis Lua 原子预减库存 + 一人一单判重（原子操作）
     → ⑤ RocketMQ 事务消息异步下单（半消息 → 本地事务 → commit/rollback）
     → ⑥ 前端轮询结果（QUEUING → SUCCESS/FAIL）
```

**核心优化**：
- 本地售罄标记前移到第①级，并对标记加 10s TTL（`mall.seckill.local-mark-ttl-seconds`），多实例补货后自动自愈；P0 基线本地标记 ON/OFF 对比为 **2.16 倍**（476→1026），P5 前移后拒绝路径峰值 **2539 QPS**（c=100，P0 基线 787）
- 秒杀商品信息走 Caffeine 本地缓存（5s），热路径省掉 2 次 Redis 往返
- 认证信息 Caffeine 缓存 30s，每请求省掉 3 次 DB 查询（用户 + 角色 + 权限）
- Lua 预减成功顺带删除结果 key，省掉 1 次独立 `DEL`

## 分布式事务与一致性设计

秒杀链路涉及 Redis 预减库存、MQ 异步下单、DB 扣减与订单落库多个异构数据源，**无法用单机事务强一致**。

### 秒杀下单：RocketMQ 事务消息（默认链路）

`mall.rocketmq.enabled=true`（`docker-compose.yml` 默认开启）时使用事务消息：

```
1. 发送半消息（对消费者不可见）
2. executeLocalTransaction：Redis Lua 原子预减 + 一人一单，结果写入 Redis 事务标记（TTL 1h）
3. 本地事务成功 → COMMIT；售罄/重复/异常 → ROLLBACK
4. Broker 长时间未收到二次确认 → 回查 checkLocalTransaction，读 Redis 标记决定提交或回滚
```

| 本地事务状态 | 触发条件 | 半消息动作 |
|---|---|---|
| `COMMIT` | 预减成功 | 投递，消费端落单 |
| `ROLLBACK` | 已售罄 | 丢弃，不产生订单 |
| `ROLLBACK` | 一人一单命中 | 丢弃，返回重复下单 |

`mall.rocketmq.enabled=false` 时回退到 RabbitMQ + `afterCommit` 发送方案（见下方四层防线）。两条链路共存，通过配置切换，便于对比与降级。

### 为什么不用 Seata AT / 2PC

| 维度 | Seata AT / 2PC | 本项目最终一致性 |
|---|---|---|
| 性能 | 全局锁串行化、两阶段提交，秒杀高并发下吞吐骤降 | 无全局锁，Redis Lua + MQ 削峰，吞吐高 |
| 可用性 | TM/RM 多组件故障放大面，协调者宕机阻塞 | 单组件故障仅影响局部，可降级 |
| 一致性 | 强一致（所有参与者同时提交/回滚） | 最终一致（秒级内收敛，前端轮询感知） |
| 适用场景 | 对一致性要求极高的转账、库存扣减不容错 | 秒杀「抢不到」可接受，最终成功即可 |

> 面试话术：秒杀的核心矛盾是「高并发」与「不超卖」，不是「强一致」。用户通过轮询感知结果，允许短暂的排队态（QUEUING），因此最终一致性完全满足业务需求。引入 Seata 反而会因全局锁成为吞吐瓶颈，得不偿失。

### 一致性保障四层防线

```
┌─────────────────────────────────────────────────────────────────────┐
│  ① 事务提交后发送：业务 DB 事务提交成功后才发 MQ                       │
│     （TransactionSynchronization.afterCommit）                        │
│     避免「事务回滚但消息已发出」导致的脏消息；                           │
│     极端情况（提交后、发 MQ 前进程宕机）可能丢消息，                      │
│     由消费端幂等 + 前端轮询感知失败 + 用户重试兜底                     │
├─────────────────────────────────────────────────────────────────────┤
│  ② 消费幂等：mq_consume_log 表 message_id 唯一索引                      │
│     INSERT IGNORE 原子写入，同一条消息只消费一次                         │
│     （与业务同事务：成功则日志留存，失败回滚则日志一并回滚允许重试）        │
├─────────────────────────────────────────────────────────────────────┤
│  ③ 业务兜底：sms_seckill_order.uk_user_sku 唯一索引 + DB 条件扣减         │
│     WHERE stock>=n 防超卖，唯一索引防一人多单                           │
├─────────────────────────────────────────────────────────────────────┤
│  ④ 失败补偿：消费异常时归还 Redis 预减库存 + 移除一人一单标记              │
│     让用户可重试；超时未支付订单经死信队列 CAS 关单回补库存                │
└─────────────────────────────────────────────────────────────────────┘
```

> 说明：本项目未引入独立的「事务消息表（Outbox）」。生产环境若对消息可靠性要求更高（零丢失），可在 ① 层升级为 Outbox 模式：业务事务内同时写 `mq_outbox` 表，由独立定时任务/异步线程扫描并发送 MQ，发送成功后标记已发送。当前 afterCommit 方案在「DB 已提交、MQ 未发出、进程宕机」的极小窗口内可能丢消息，已通过 ②③④ 层兜底，满足秒杀「允许重试、最终成功」的业务语义。

### MQ 消费幂等实现

每条消息携带生产端生成的 `messageId`（UUID），消费者进入业务逻辑前先执行：

```sql
INSERT IGNORE INTO mq_consume_log (message_id, queue_name, status)
VALUES (?, ?, 1);
-- 返回 1 = 首次消费，执行业务；返回 0 = 重复消息，直接 ACK 跳过
```

**关键设计**：`mq_consume_log` 写入与业务处理处于**同一数据库事务**：
- 业务成功提交 → 日志记录留存，后续 MQ 重投被唯一索引拦截
- 业务失败回滚 → 日志记录一并回滚，消息可被重新消费（允许重试）
- 解决的问题：消费者处理成功但 ACK 前宕机，MQ 重投同一消息导致**重复落单 / 重复关单 / 重复回补库存**

当前两个消费者均已接入：
- `SeckillOrderListener`（秒杀异步下单）：防止重复生成秒杀订单
- `OrderCloseListener`（超时关单）：防止重复回补库存

## JWT 安全设计

采用 **access token（短期）+ refresh token（长期）** 双 token 方案，并通过 **refresh 轮换 + Redis 黑名单** 实现主动登出与 token 泄露止损。为降低 XSS 风险，refresh token 通过 **httpOnly Cookie** 下发，前端 JS 不可读。

### 核心机制

```
┌─────────────────────────────────────────────────────────────────────┐
│  ① 登录：签发 access(2h,响应体) + refresh(7d,httpOnly Cookie)           │
│     access 存前端内存（Pinia state），不写 localStorage                 │
│     refresh 由浏览器自动携带，JS 不可读，SameSite=Lax 防 CSRF           │
├─────────────────────────────────────────────────────────────────────┤
│  ② 访问业务：JwtAuthenticationFilter 校验 access 签名 + 黑名单         │
│     黑名单命中 → 拒绝认证（401），前端自动 refresh 续签后重试            │
├─────────────────────────────────────────────────────────────────────┤
│  ③ 刷新：前端 401 时调用 /api/auth/refresh（Cookie 自动携带）           │
│     refresh 成功后，旧 refresh token 写入 Redis 黑名单                   │
│     （TTL = 剩余有效期），签发新双 token → 旧 token 立即作废           │
├─────────────────────────────────────────────────────────────────────┤
│  ④ 登出：调用 /api/auth/logout，当前 access token 写入黑名单            │
│     （TTL = 剩余有效期），清除 refresh Cookie，无需等待自然过期          │
└─────────────────────────────────────────────────────────────────────┘
```

**Token 存储安全对比**：

| Token | 存储位置 | JS 可读 | XSS 风险 | 说明 |
|---|---|---|---|---|
| access token | 前端内存（Pinia state） | 是 | 中 | 短期（2h），刷新页面后丢失，可通过 refresh 续签 |
| refresh token | httpOnly Cookie | 否 | 低 | 长期（7d），浏览器自动携带，SameSite=Lax 防 CSRF |

**黑名单设计要点**：
- key 取 token 的 MD5 摘要，避免超长 JWT 直接作为 Redis key
- TTL 取 token 剩余有效期，token 自然过期后记录自动消失，无需额外清理任务
- 仅做存在性判断，value 固定为 `"1"`

> **CSRF 说明**：refresh Cookie 设置 `SameSite=Lax`，跨站 POST 请求不会携带该 Cookie，从而防止 CSRF 攻击。生产环境通过 HTTPS + `Secure` 属性进一步加固（`mall.jwt.refresh-cookie-secure=true`）。若需更强保护，可额外引入 CSRF Token 机制。

## 多级缓存设计

商品详情、秒杀场次列表、秒杀 SKU 列表等热数据采用 **Caffeine 本地 L1 + Redis L2** 多级缓存，降低 Redis 带宽与网络开销：

```
查询请求 → Caffeine(L1, 命中零网络IO)
         → Redis(L2, 命中后回填 L1)
         → DB(回源, Redisson 锁防击穿 + DCL, 同时写 L1 和 L2)
```

- **L1（Caffeine）**：`maximumSize=1000`，`expireAfterWrite=300s`（配置项 `mall.cache.spu-detail-local-ttl`）
  - 覆盖：SPU 详情、秒杀场次列表、场次 SKU 列表
- **L2（Redis）**：基础 TTL 1800s + 0~600s 随机抖动（防雪崩）
- **失效策略**：Cache Aside，商品更新/删除、秒杀 SKU 上架时先删 Redis 再删 Caffeine
- **空值缓存**：不存在的商品也写入 L1/L2（空字符串占位），防穿透

## 分布式链路追踪

采用 **Micrometer Tracing + Brave**（Spring Boot 3 替代 Sleuth 的标准方案），链路数据上报 **Zipkin** 可视化。

### 核心特性

- **传播协议**：W3C `traceparent` 标准头，跨 Nginx → app1/app2 → MQ 自动透传
- **自动埋点**：Spring Boot 自动为 Web MVC、RestTemplate、RabbitMQ 收发、Lettuce(Redis) 创建 span
- **日志关联**：日志格式携带 `traceId`/`spanId`，可按 traceId 串联多实例日志
- **响应头回传**：`TraceIdResponseFilter` 将 traceId 写入响应头 `X-Trace-Id`，前端报错时可直接查链路

### 配置项

```yaml
management:
  tracing:
    sampling:
      probability: 1.0          # 采样率，生产建议 0.1~0.5
    propagation:
      type: w3c                 # W3C traceparent
  zipkin:
    tracing:
      endpoint: http://zipkin:9411/api/v2/spans
```

### 使用方式

```bash
# 启动后访问 Zipkin UI
http://localhost:19411

# 发起任意请求后，从响应头 X-Trace-Id 取 traceId，在 Zipkin 搜索即可看到完整调用链：
# HTTP 请求 → Redis 操作 → RabbitMQ 发送/消费 → MySQL 查询
```

## 快速开始（Docker Compose）

前置：安装 Docker Desktop、Node.js 18+。

```bash
# 1. 构建前端（生成 frontend/dist，Nginx 托管）
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run build
cd ..

# 2. 一键启动全栈
#    MySQL（主库 + 2 订单库）/Redis/RabbitMQ/RocketMQ/Nacos/Sentinel/Elasticsearch
#    双应用实例/Nginx+前端/Prometheus/Grafana/Zipkin
docker compose up -d

# 3. 等待中间件与应用健康（首次启动含 ES 建索引，约 90s），检查容器状态
docker compose ps

# 4. 访问前端首页
#    http://localhost:8090
#    接口文档：http://localhost:8090/doc.html

# 5. 健康检查
curl http://localhost:8090/api/health

# 6. 停止
docker compose down
# 清理数据卷
docker compose down -v
```

### 端口映射

| 服务 | 宿主端口 | 说明 |
|---|---|---|
| Nginx（应用入口） | 8090 | 所有 API / 文档 / 前端 |
| Grafana | 13000 | 监控大盘（admin / admin123） |
| Zipkin | 19411 | 分布式链路追踪 UI |
| Prometheus | 9090 | 指标查询 |
| MySQL（主库） | 13306 | root / root123456 |
| MySQL（订单分库 ds1） | 13307 | root / root123456 |
| MySQL（订单分库 ds2） | 13308 | root / root123456 |
| Redis | 6380 | 密码 mall123456 |
| RabbitMQ 管理台 | 15672 | mall / mall123456 |
| RocketMQ NameServer | 9876 | Broker 10911 |
| Nacos 控制台 | 8848 | nacos / nacos（鉴权已关闭） |
| Sentinel 控制台 | 8858 | sentinel / sentinel |
| Elasticsearch | 9200 | 无鉴权（仅本地） |

> 容器数量较多，8GB 内存以下的机器建议只启动核心链路：
> `docker compose up -d mysql mysql-order-0 mysql-order-1 redis rabbitmq rocketmq-namesrv rocketmq-broker app1 app2 nginx`

### 端到端冒烟测试

```bash
# 压测模式（关闭验证码与限流，仅用于本地压测/冒烟）
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d

powershell -ExecutionPolicy Bypass -File scripts/phase7-smoke.ps1
# 预期结尾输出: SMOKE_OK
```

## 本地开发

前置：JDK 17、Maven 3.9+、Node.js 18+。中间件建议直接复用 compose 容器（dev profile 默认连宿主机映射端口：MySQL 13306、Redis 6380、RabbitMQ 5672；分库分表只在 prod profile 启用，Nacos/Sentinel 默认关闭，RocketMQ 关闭时走 RabbitMQ 回退链路）。

```bash
# 1. 启动中间件（首次启动会自动执行 sql/init/01-09.sql）
docker compose up -d mysql redis rabbitmq

# 2. 启动后端（dev profile）
mvn spring-boot:run

# 3. 启动前端（开发模式，热更新，/api 代理到 localhost:8090）
cd frontend
npm install
npm run dev
# 浏览器访问 http://localhost:5173

# 4. 后端打包
mvn clean package -DskipTests
java -jar target/mall-seckill.jar
```

`application-prod.yml` 中所有中间件地址、密码、密钥均支持环境变量覆盖，便于容器化部署。

## API 概览

| 模块 | 前缀 | 说明 |
|---|---|---|
| 认证 | `/api/auth/*` | 登录 / 注册 / 刷新令牌（轮换）/ 登出（黑名单） / 当前用户信息 |
| 商品 | `/api/spu/*`, `/api/category/*` | SPU/SKU、分类树 |
| 购物车 | `/api/cart/*` | 加购 / 修改数量 / 勾选 / 列表 |
| 订单 | `/api/order/*` | 创建 / 支付 / 发货 / 确认 / 取消 / 列表 |
| 秒杀 | `/api/seckill/*` | 场次列表 / 换取路径 / 执行抢购 / 结果轮询 |
| 搜索 | `/api/search/spu` | 关键词（IK 分词）/ 分类 / 价格区间 / 排序 / 高亮 |
| 管理端 | `/api/admin/*` | 商品、分类、订单、秒杀活动/场次/SKU 管理 |
| 搜索运维 | `/api/admin/search/reindex` | 全量重建 ES 索引 |
| 治理 | `/api/admin/governance/state` | 查看 Nacos/Sentinel 当前治理状态 |
| 健康 | `/api/health` | 存活探针 |

公开端点（无需 token）：`/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`、`/api/health`、`/doc.html`、`/v3/api-docs/**`。

## 测试

```bash
mvn test
# Tests run: 71, Failures: 0, Errors: 0
```

测试金字塔：

| 层级 | 数量 | 说明 |
|---|---|---|
| 单元测试 | 67 | Mockito 纯单测，隔离 DB/MQ/Redis，毫秒级。覆盖秒杀漏斗六级出口、订单状态机、购物车、验证码、限流切面、JWT 黑名单、多级缓存（Caffeine→Redis→DB 回源与失效）、TraceId 响应过滤器、RocketMQ 事务监听器三种本地事务状态、ES 搜索降级 |
| 集成测试 | 4 | JVM 内嵌 Redis（embedded-redis），验证 Lua 原子预减、一人一单判重、库存耗尽售罄、结果轮询 |

## 性能压测

完整报告（环境、方法、矩阵、瓶颈分析）见 [benchmark-report-v2.md](benchmark-report-v2.md)。

| 指标 | P0 基线 | P5 优化后 |
|---|---|---|
| 售罄拒绝 QPS（c=100） | 787 | **2539**（峰值） |
| 售罄拒绝 QPS（c=200） | — | **2222** |
| 售罄拒绝 QPS（c=400） | 555（22 个网络错误） | 1128~1334（0 错误） |
| 成功下单 QPS（c=200，一人一单） | — | **1013**（峰值）/ 847（中位） |
| 错误率 | 0.44% | <0.01% |
| 不超卖 / 不重复 | 0 | 0（8 分片累计 27005 单验签） |

> 压测客户端 `scripts/SeckillBench.java` 运行在 compose 网络内的容器中，直连 Nginx，避开 Windows 端口转发噪声。同一配置跨轮次波动 1.0k~2.5k，主因是宿主空闲内存仅约 0.4~0.7GB；成功路径瓶颈为「同一 JVM 既发事务消息又消费 + Atomikos XA 写 3 个 MySQL」的 CPU 消耗。

复现命令：

```bash
# 压测模式启动（关闭限流/验证码/Zipkin 采样）
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d

# 全矩阵：售罄拒绝 + 成功下单（容器内客户端）
powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -Mode all -SkuId 4 `
  -RejectTotal 8000 -SuccessUsers 3000 -SuccessConcurrency 200

# 只跑售罄拒绝（c=100/200/300）
powershell -ExecutionPolicy Bypass -File scripts/phase5-bench.ps1 -Mode reject -RejectConcurrency 100,200,300
```

## 监控与可观测性

- **指标暴露**：`/actuator/prometheus`（Micrometer Registry）
- **核心指标**：
  - `mall_seckill_execute_total{result=...}`：秒杀 execute 各出口计数（queued/sold_out/repeated/invalid_path/...）
  - `mall_seckill_execute_duration_seconds`：execute 耗时直方图
  - `mall_seckill_consume_total{result=...}`：异步消费结果计数
  - `mall_seckill_stock_remaining`：剩余库存 Gauge
  - `mall_rabbitmq.queue.messages`：队列堆积深度
- **Grafana 大盘**：启动后自动 provisioning「秒杀可观测大盘」，访问 http://localhost:13000
- **Prometheus**：http://localhost:9090，targets 含 app1/app2 双实例
- **链路追踪**：Zipkin http://localhost:19411，响应头 `X-Trace-Id` 可直接定位调用链
- **注册/配置**：Nacos http://localhost:8848（服务列表、配置项、Sentinel 规则）
- **流量治理**：Sentinel http://localhost:8858（热点参数限流规则，持久化在 Nacos）
- **搜索**：Elasticsearch http://localhost:9200，索引 `pms_spu`

## CI/CD

`.github/workflows/ci.yml` 在 push/PR 到 main 时触发：

1. **test**：JDK 17 + Maven，`mvn verify` 跑全部 71 个用例作为合并门禁；失败上传 surefire 报告
2. **docker-build**：测试通过后构建多阶段 Docker 镜像，验证 Dockerfile 可构建

## 项目结构

```
mall-seckill/
├── frontend/              # Vue 3 轻量前端（Vite + Element Plus + Pinia）
│   ├── src/api/           # axios 请求封装 + JWT 拦截器
│   ├── src/views/         # 登录 / 商品列表 / 秒杀 / 订单页
│   ├── src/stores/        # Pinia 鉴权 store
│   └── src/router/        # 路由 + 登录守卫
├── src/main/java/com/mall/
│   ├── controller/          # REST 接口（client + admin）
│   ├── service/impl/        # 业务逻辑
│   ├── entity/              # MyBatis-Plus 实体
│   ├── mapper/              # DAO
│   ├── dto/  vo/  model/    # 请求/响应/缓存模型
│   ├── config/              # 配置类（Redis/Rabbit/RocketMQ/Security/Sentinel/...）
│   ├── security/            # JWT 认证授权 + 用户信息本地缓存
│   ├── mq/                  # RabbitMQ 生产者/消费者（延迟关单）
│   ├── mq/rocketmq/         # RocketMQ 事务消息：生产者 / 本地事务 / 回查 / 消费
│   ├── search/              # ES 检索、索引同步与降级
│   ├── metrics/             # Micrometer 埋点与导出
│   ├── common/aspect/       # 限流切面
│   └── common/exception/    # 全局异常处理
│
├── src/main/resources/
│   ├── application-prod.yml # 容器化配置（全部支持环境变量覆盖）
│   └── sharding.yaml        # ShardingSphere 8 分片 + XA 规则
├── src/test/java/com/mall/  # 单元/集成测试
├── sql/init/                # 01-09 初始化脚本（schema + 种子数据）
├── sql/sharding/            # 订单分库建表脚本（ds1/ds2）
├── scripts/                 # SeckillBench.java + phaseN 验证/压测脚本
├── docker/                  # Nginx/RocketMQ/Prometheus/Grafana/ES 配置
├── docs/                    # 01–07 架构/Redis/MQ/面试题与交易中台升级方案
└── benchmark-report-v2.md   # P0~P5 压测与瓶颈报告
```

## 前端页面

| 页面 | 路由 | 说明 |
|---|---|---|
| 登录 | `/login` | 用户名密码登录，access token 存内存，refresh token 存 httpOnly Cookie |
| 商品列表 | `/` | SPU 分页展示，搜索 |
| 秒杀 | `/seckill` | 场次选择 → 验证码 → 动态路径 → 抢购 → 轮询结果（完整三步链路） |
| 我的订单 | `/orders` | 订单列表、模拟支付、取消、确认收货 |

前端与后端通过 Nginx 同源部署（`/api` 代理至后端集群），JWT 自动携带，401 自动跳转登录。

## 默认账号

| 角色 | 用户名 | 密码 |
|---|---|---|
| 管理员 | admin | admin123 |

数据库初始化脚本位于 `sql/init/`，Docker 首次启动时自动执行。

## License

MIT
