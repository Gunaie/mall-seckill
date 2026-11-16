# 文档二：Redis 与 Lua 脚本深度剖析

> 目标：把项目里所有 Redis 的用法讲到"随便挑一行代码都能说清为什么"。
> 本文档对应：CacheConstants、SeckillServiceImpl、OrderServiceImpl、CaptchaServiceImpl、AccessLimitAspect、PmsSpuServiceImpl、RedissonConfig。

---

## 1. Redis 在项目里的完整用途地图

先建立全局观，项目用到了 Redis 的全部五种基础结构：

| 数据结构 | 项目用途 | 具体 key |
|---|---|---|
| **String** | 秒杀库存计数 | `seckill:stock:{seckillSkuId}` |
| **String** | 秒杀动态路径 | `seckill:path:{userId}:{seckillSkuId}` = MD5 |
| **String** | 下单结果 | `seckill:result:{userId}:{seckillSkuId}` = orderId/FAIL |
| **String** | 幂等令牌 | `order:token:{userId}:{token}` = 1 |
| **String** | 验证码答案 | `seckill:captcha:{token}` = 答案 |
| **String** | 商品→场次索引 | `seckill:sku:session:{seckillSkuId}` = sessionId |
| **String** | JWT 黑名单 | `jwt:blacklist:{tokenMd5}` |
| **String** | 场次列表缓存 | `cache:seckill:sessions:{date}` = JSON List |
| **String** | 商品详情缓存 | `cache:spu:detail:{spuId}` = VO 或空串 |
| **Set** | 一人一单判重集合 | `seckill:ordered:{seckillSkuId}`，member=userId |
| **Hash** | 场次秒杀商品表 | `seckill:skus:{sessionId}`，field=seckillSkuId，value=商品缓存JSON |
| **Hash** | 用户购物车 | `cart:user:{userId}`，field=skuId，value=购物项JSON |
| **ZSet** | 滑动窗口限流 | `access:limit:{uri}:{身份}`，score=毫秒时间戳 |

**面试加分说法**："选结构是按操作特征来的——库存要增减用 String；一人一单是'判断成员在不在'用 Set 的 SISMEMBER，O(1)；购物车一个用户多个商品且要按 SKU 单独改，用 Hash；限流要按时间范围统计个数，用 ZSet 的 ZREMRANGEBYSCORE + ZCARD。"

### 两个 RedisTemplate 的区别（代码细节，容易被问）

项目同时注入了 `RedisTemplate<String,Object>` 和 `StringRedisTemplate`：

- `RedisTemplate`：value 用 JSON 序列化器，存 Java 对象（商品缓存、购物车）
- `StringRedisTemplate`：key/value 都是原始字符串。**所有 Lua 脚本必须用它**——如果用 JSON 模板，参数 `"1"` 会被序列化成 `"\"1\""`（带引号），Lua 里 `tonumber` 直接失败。源码 SeckillServiceImpl 第106-110行专门注释了这一点。

---

## 2. Lua 脚本四段精讲

### 2.1 为什么要用 Lua（先把原理说透）

Redis 执行命令是单线程的，而 **Lua 脚本会被当作一个整体执行，执行期间不会插入其他客户端命令**（原子性）。这正好解决秒杀的核心难题：

> "查库存 + 判断这个人买没买过 + 扣减库存"是多个命令，如果分开执行，中间会插进别的请求，就会超卖或一人多单。

其他好处：一次脚本调用完成多步操作，**减少网络 RTT**（原本 N 次往返变 1 次）；脚本可以复用（缓存到 Redis，用 EVALSHA）。

注意：Lua 的原子性来自 Redis 单线程，不是 Lua 语言本身有事务能力。脚本要尽量短，长脚本会阻塞其他所有命令。

### 2.2 脚本一：秒杀库存预减（核心中的核心）

位置：SeckillServiceImpl 第138-153行

```lua
-- KEYS[1] = seckill:stock:{seckillSkuId}      库存计数
-- KEYS[2] = seckill:ordered:{seckillSkuId}    已购用户集合
-- ARGV[1] = 购买数量（本项目固定 "1"）
-- ARGV[2] = userId

if redis.call('sismember', KEYS[2], ARGV[2]) == 1 then
    return -1                     -- ① 这个用户已经买过了
end
local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil or stock < tonumber(ARGV[1]) then
    return 0                     -- ② key 不存在 或 库存不足 → 售罄
end
redis.call('decrby', KEYS[1], ARGV[1])    -- ③ 库存减 N
redis.call('sadd', KEYS[2], ARGV[2])      -- ④ 用户加入已购集合
return 1                         -- ⑤ 预减成功
```

**逐行讲解要点**：

1. **先判重再判库存**：判重成本是一次 SISMEMBER，放最前面。重复抢购的人最多（脚本连点），先拦掉。
2. **stock == nil 的分支**：如果 Redis key 过期/丢失，不能当成有库存继续扣，直接按售罄处理（fail-safe，宁可错拒不可超卖）。
3. **tonumber 必须显式转**：Lua 从 Redis GET 拿到的是字符串，数字比较前要转。
4. **为什么不用 DECR 然后判断负数**：DECR 是直接扣，扣成负数后再回补存在并发窗口。本脚本是"先判断够不够，够才扣"，不会出现负库存。
5. **返回值设计成 -1/0/1**：Java 端用一个 Long 就能区分三种结果，代码清晰：

```java
if (result == null || result == 0L) { 本地售罄标记置位; throw "已抢光"; }
if (result == -1L) { throw "请勿重复抢购"; }
// 1：继续发 MQ
```

**调用方式**（注意 KEYS 和 ARGV 分开传）：

```java
stringRedisTemplate.execute(PRE_DEDUCT_SCRIPT,
    List.of(stockKey(seckillSkuId), orderedSetKey(seckillSkuId)),  // KEYS
    "1", String.valueOf(userId));                                  // ARGV
```

### 2.3 脚本二：下单幂等令牌一次性消费

位置：OrderServiceImpl 第89-99行

```lua
-- KEYS[1] = order:token:{userId}:{token}
if redis.call('get', KEYS[1]) then
    return redis.call('del', KEYS[1])   -- 令牌存在 → 删除并返回 1
else
    return 0                            -- 不存在（没用过的/过期的）→ 0
end
```

**解决什么问题**：用户双击"提交订单"、网络抖动重试、刷新重放 POST，都会带着同一个 token 打两次。"GET 判断存在 + DEL"如果分两条命令，两个并发请求可能都 GET 到了再各自 DEL，都下单成功。Lua 保证原子：**只有一个请求能看到令牌存在并删掉它**，第二个拿到 0 被拒绝。

token 在进入结算页时由 `issueToken()` 生成（UUID，短 TTL），与用户绑定，别人偷了 token 也用不了（key 里含 userId）。

### 2.4 脚本三：验证码一次性消费

位置：CaptchaServiceImpl 第54-65行

```lua
-- KEYS[1] = seckill:captcha:{token}, ARGV[1] = 用户输入答案
local answer = redis.call('get', KEYS[1])
if answer == false then
    return 0                  -- 不存在/已过期
end
redis.call('del', KEYS[1])    -- 无论对错，读了就删（一次性！）
if answer == ARGV[1] then return 1 else return -1 end
```

**关键点：为什么答错也要删**：验证码必须一次性。如果答错不删，脚本可以对同一个 captchaToken 暴力枚举答案（算术题答案空间很小）。用后即删，想再试只能重新获取图片（抬高机器成本）。

注意 Lua 里 Redis GET 不存在时返回的是 `false`（Lua 布尔），不是 nil，所以判 `answer == false`。

### 2.5 脚本四：滑动窗口限流

位置：AccessLimitAspect 第56-68行

```lua
-- KEYS[1] = access:limit:{uri}:{身份}
-- ARGV[1] = 当前时间戳ms   ARGV[2] = 窗口大小ms
-- ARGV[3] = 窗口内最大次数  ARGV[4] = 本次请求唯一ID(UUID)

redis.call('zremrangebyscore', KEYS[1], 0, tonumber(ARGV[1]) - tonumber(ARGV[2]))
                    -- ① 删掉窗口左边界之前的所有旧请求
if tonumber(redis.call('zcard', KEYS[1])) >= tonumber(ARGV[3]) then
    return 0        -- ② 当前窗口内请求数已达上限 → 限流
end
redis.call('zadd', KEYS[1], ARGV[1], ARGV[4])   -- ③ 记录本次请求
redis.call('pexpire', KEYS[1], ARGV[2])         -- ④ 给 key 设过期，冷窗口自动清理省内存
return 1
```

**算法原理（要能讲）**：
- ZSet 的 member 是每次请求的唯一标识，score 是请求时间戳
- 一个窗口里的请求 = score 落在 `[now-window, now]` 的 member
- 每次请求先把窗口外的老数据删掉，再数当前有多少个（ZCARD），没超限就把自己加进去

**为什么比固定窗口好**：固定窗口（如每分钟100次）在窗口边界有临界问题——第59秒来100个、第61秒又来100个，实际瞬间200个流量都放行。滑动窗口按真实时间滑动统计，没有这个突刺。

**为什么要 pexpire**：如果某用户只请求一次就再也不来了，ZSet 会永远留着，设置与窗口等长的过期让冷 key 自动消失。

**四步为何也要 Lua**：清理、计数、写入之间不能插入别的请求，否则计数不准。

---

## 3. 缓存三大问题（必考，且你项目里全部有对应实现）

### 3.1 缓存穿透（Penetration）

- **问题**：请求一个**数据库里根本不存在**的数据（如 spuId=-1、spuId=999999），缓存永远不命中，每次都打到 DB。恶意攻击可以用大量不存在的 ID 把 DB 打挂。
- **项目方案：空值缓存**。见 PmsSpuServiceImpl 第143-148行：DB 查不到时，往缓存写一个**空值占位符**（`NULL_PLACEHOLDER = ""`），但 TTL 较短（`nullValueTtl`）。后续同样的请求在缓存层就被挡住。
- 本地 Caffeine 也缓存了空值（第91-94、136-139行），连 Redis 都不用打。
- **备选方案（要知道但没全用）**：布隆过滤器（请求前先判断 ID 是否可能存在，适合海量数据集合，但存在误判率、维护成本）。本项目数据量不大，空值缓存足够，面试时要能说出"为什么选空值缓存而不是布隆"——ID 空间小、实现简单、空值 TTL 短不浪费内存。

### 3.2 缓存击穿（Breakdown / Hotspot Invalid）

- **问题**：某个**热点 key 突然过期**的瞬间，大量并发请求同时涌入，全都没命中缓存，同时打到 DB 重建，DB 压力骤增。
- **项目方案：分布式锁 + 双重检查（DCL）**。见 PmsSpuServiceImpl 第114-155行：
  1. 缓存没命中，先尝试 Redisson 锁 `lock:spu:detail:{id}`（tryLock 等待5s）
  2. **只有抢到锁的线程能回源 DB 重建缓存**
  3. 抢到锁后**二次检查缓存（DCL）**：可能在等锁期间已被别的线程重建好，直接用，不重复查库
  4. 没抢到锁的线程：sleep 150ms 后重试读缓存，读到就返回；还读不到返回"稍后再试"，绝不直接打库
- **逻辑互斥锁（备选）**：Redis SET NX 也能实现，本项目直接用 Redisson，省去手写解锁和超时处理。

### 3.3 缓存雪崩（Avalanche）

- **问题**：**大量 key 在同一时刻集体过期**，或 Redis 整体宕机，导致大批请求同时打到 DB。
- **项目方案：TTL 随机抖动**。见第149-152行：

```java
long ttl = 基础TTL + ThreadLocalRandom.nextLong(抖动范围 + 1);
```

  让不同 key 的过期时间被随机打散，不会同一刻大面积失效。
- **防 Redis 宕机**（要能说出手段）：Redis 哨兵/集群高可用、本地 Caffeine 兜底、限流降级保护 DB。本项目有本地缓存 + 限流作为兜底。

### 三者区别一句话记忆

```
穿透：查的数据 DB 里压根没有     → 空值缓存/布隆过滤器
击穿：某个热点 key 过期的瞬间     → 互斥锁只放一个请求重建
雪崩：大量 key 同时过期/Redis挂   → TTL 随机化 + 高可用 + 兜底
```

---

## 4. Cache Aside 与多级缓存一致性

### 4.1 Cache Aside 模式（项目采用）

- **读**：先读缓存，没有再读 DB 并回填缓存
- **写**：**先更新数据库，再删除缓存**（不是更新缓存！）。见 PmsSpuServiceImpl 第208-209、318-323行。

### 4.2 高频追问：为什么是"删缓存"而不是"更新缓存"

1. 更新缓存可能更新了一个根本没人读的冷数据，浪费（lazy 思想）
2. 写频繁时，多个更新请求并发，缓存可能被写成旧值（先到的请求后写缓存）
3. 删除是幂等操作，下次读时用最新 DB 重建，天然一致

### 4.3 高频追问：先删缓存还是先更库

项目选**先更库再删缓存**。分析两种错误场景：

- 先删缓存再更库：删完缓存、还没更完库时，有并发读请求读到旧库数据并回填缓存 → 缓存里一直是旧值，直到下次过期。
- 先更库再删缓存：极端情况下"读请求恰好在读库（读到旧值）→ 写请求更完库并删缓存 → 读请求把旧值写回缓存"才会不一致，但这个窗口要求读库发生在更库之前、写回发生在删缓存之后，概率极低，且读通常比写快，工程上可接受。
- 即使发生，缓存有 TTL 最终会过期；还可以用**延迟双删**（更库 → 删缓存 → 延迟一小段时间再删一次）兜底。

### 4.4 多级缓存的失效顺序

项目有 Caffeine L1 和 Redis L2，失效时（evictDetailCache）：

```
先删 Redis → 再 invalidate Caffeine
```

预热秒杀商品时（uploadToRedis 第617-619行）：清本地售罄标记 + 失效 Caffeine 中的场次 SKU 列表。

**多级缓存的固有问题（要诚实承认）**：Caffeine 是每实例各自一份，没有跨实例广播失效时，实例 A 删了自己的 L1，实例 B 的 L1 仍可能是旧值直到 TTL。项目靠**给 L1 设较短 TTL（300s）且 L1 TTL < L2 TTL** 把脏数据窗口控制在分钟级。生产可接 Redis Pub/Sub 广播失效（如 Redisson Topic）。被问到时主动讲这个取舍，比声称"强一致"更可信。

### 4.5 Caffeine 补充知识（可能被问）

- 为什么不用 Guava Cache：Guava Cache 已停止演进，Caffeine 是其继任者，性能更高
- W-TinyLFU 算法：结合 LRU 和 LFU 优点，用 Count-Min Sketch 统计频率，防缓存污染，命中率高
- 三个过期 API 区别：expireAfterWrite（写后固定过期）、expireAfterAccess（访问后过期）、refreshAfterWrite（异步刷新，返回旧值同时后台加载）。项目用 expireAfterWrite。

---

## 5. Redisson 分布式锁深挖

### 5.1 项目两处用锁

| 锁 key | 位置 | 作用 |
|---|---|---|
| `lock:spu:detail:{spuId}` | PmsSpuServiceImpl | 热点 key 互斥重建（防击穿） |
| `lock:seckill:order:{userId}:{seckillSkuId}` | SeckillServiceImpl | 同一用户秒杀消息串行（一人一单业务兜底） |

调用：`lock.tryLock(等待时间, 持锁时间, 单位)`，finally 里 `if (locked) unlock()`。

### 5.2 高频题：Redis 分布式锁演进（要能讲出完整过程）

1. **SETNX + EXPIRE 分两条命令**：加锁和设过期不是原子的，加锁后宕机 → 死锁。❌
2. **SET key value NX EX seconds**：一条命令原子加锁并设过期，解决宕机死锁。✅ 但解锁有问题。
3. **解锁误删别人的锁**：A 锁过期后 B 拿到锁，A 执行完直接 DEL，删了 B 的锁。解决：value 存唯一标识（UUID），解锁用 Lua 判断"是自己的锁才删"。
4. **锁过期了业务还没执行完**：A 持锁处理慢任务，锁自动释放，别人又拿到锁。解决：**看门狗（Watchdog）自动续期**——Redisson 默认锁不指定 leaseTime 时，后台线程每 10s 检查客户端还活着就把锁续到 30s。
   - 注意：**项目显式传了 leaseTime（10s/30s），此时看门狗不生效**，到点自动释放。要能解释这样设计的原因：锁只保护很短的 DB 操作，固定租约避免客户端宕机后锁被无限续期。
5. **Redis 主从切换丢锁**：锁写在 master 还没同步到 slave，master 挂了，新 master 上没有锁，别人又拿到。Redisson 提供 **RedLock**（向多个独立节点申请，多数成功才算拿到）。但 RedLock 争议大（时钟漂移、Martin Kleppmann 的质疑），生产更常用的是 ZooKeeper/etcd 这类基于共识的锁，或者直接接受"锁用于降低并发概率 + DB 最终约束兜底"。本项目的最终防线就是 DB 条件扣减和唯一索引。

### 5.3 Redisson 锁的可重入原理

基于 Hash：key=锁名，field=客户端唯一ID+线程ID，value=重入次数。重入时 HINCREMENT 加 1，释放时减 1，减到 0 才真正删 key。加锁失败的客户端通过 **Pub/Sub 订阅锁释放消息**，被唤醒后重试，避免轮询浪费。

### 5.4 为什么秒杀消费还要 Redisson 锁（Lua 不是保证一人一单了吗）

纵深防御：Lua 保证的是**同步入口**的一人一单；但存在消息重复投递、网络分区等边界情况。消费端锁 + `sms_seckill_order` 查询 + DB 唯一索引，三层保证即使有漏网并发消息，也不会产生两张订单。

---

## 6. 本地内存售罄标记深挖（项目亮点，必被问）

实现：`ConcurrentHashMap<Long,Boolean> localSoldOutMap`

### 6.1 工作流程

```
Lua 返回 0（售罄）→ map.put(seckillSkuId, true)
execute 入口（在查缓存、调 Redis 之前）先查 map → true 直接拒绝
清除时机：
  - 重新预热 uploadToRedis：remove
  - 消费失败补偿（库存还回去）：remove
  - 订单取消回补库存 restoreAfterOrderCancel：remove
```

### 6.2 为什么放在所有检查的最前面

它是成本最低的拒绝方式：纯 JVM 内存读，无网络 IO。秒杀商品抢空后往往还有海量流量继续涌入（大家不信邪继续点），这批流量用内存标记挡掉收益最大。P0 前基线压测证明 QPS 从 476 提到 1026（2.16 倍）；P5 将标记前移到漏斗第 1 级并加 10s TTL 后，同口径拒绝路径峰值 2539 QPS（见 [benchmark-report-v2.md](../benchmark-report-v2.md) 第 7 节）。

### 6.3 高频追问：多实例下这个标记会不会不一致

会，要承认并解释为什么没关系：

- 实例 A 第一次收到售罄响应，只置位 A 自己的标记；实例 B 不知道。但**这只影响效率不影响正确性**——B 上的请求走一次 Lua，拿到 0，也置位 B 的标记，之后就内存拒绝了。相当于每个实例"自学"一次（最多一次 Redis 调用）就收敛。
- 库存回补时，执行回补的实例清自己的标记，其他实例可能短暂保留旧标记（明明回补了 1 件却拒绝请求）。这是**短暂的可用性损失**，靠 TTL 或重新预热广播收敛。秒杀场景下库存回补极少，可接受。
- 如果要强一致，用 Redis Pub/Sub 广播标记变更即可。

### 6.4 为什么用 ConcurrentHashMap 而不是 volatile boolean

- 一个应用有多个秒杀商品，需要按 seckillSkuId 分别标记 → Map
- 高并发读写 → ConcurrentHashMap 保证线程安全；读用 get，写用 put，复合操作还可用 putIfAbsent/compute
- 不需要锁：标记的置位幂等，多线程同时 put(true) 结果一样

---

## 7. Redis 基础八股联动（项目相关的都要会）

1. **为什么单线程还快**：纯内存操作；IO 多路复用（epoll）单线程处理大量连接；无锁竞争和上下文切换。注意 Redis 6 后网络 IO 有多线程，但命令执行仍是单线程。
2. **持久化**：
   - RDB：内存快照，体积小恢复快，但可能丢最后一次快照后的数据
   - AOF：记录每条写命令，appendfsync（always/everysec/no），更安全但文件大
   - 本项目缓存/库存场景：即使是库存 key，丢失后可由 DB（seckill_stock）和重新预热恢复，所以不强依赖持久化；要能说出"库存最终以 DB 为准，Redis 是加速层"。
3. **过期与淘汰**：惰性删除 + 定期删除；内存满了按淘汰策略（noeviction、allkeys-lru、volatile-lru、lfu、random 等）。本项目 key 基本都设 TTL。
4. **缓存 vs 数据库一致性的本质**：缓存是副本，分布式下没有同时保证高性能、可用性、强一致的方案，只能按业务在"最终一致 + 短暂延迟"上取舍（CAP/BASE 思想）。
5. **bigkey/热 key**：秒杀库存是热点 key，单个 value 很小不是 bigkey；热点问题靠本地缓存和售罄标记分担；Lua 脚本要短避免阻塞。

---

## 8. 自测清单

1. 默画四个 Lua 脚本，说清每个返回值的含义和 KEYS/ARGV。
2. Lua 原子性的本质是什么？为什么脚本不能写太长？
3. 为什么 Lua 必须用 StringRedisTemplate？
4. 缓存穿透/击穿/雪崩各举一个生活类比，说出项目对应代码行。
5. 为什么写操作是"更库 + 删缓存"？先删还是先更？极端不一致怎么发生？
6. 多级缓存在多实例下的脏数据窗口怎么控制？
7. Redisson 锁演进五步，看门狗什么时候生效？项目为什么不用看门狗？
8. RedLock 有什么争议？项目用什么替代它保证正确性？
9. 本地售罄标记多实例不一致为什么不影响正确性？三个清除时机分别是什么？
