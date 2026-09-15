package com.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.constant.CacheConstants;
import com.mall.common.constant.MqConstants;
import com.mall.common.enums.OrderStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.common.util.SpecTextUtil;
import com.mall.config.OrderProperties;
import com.mall.config.SeckillProperties;
import com.mall.dto.SeckillActivitySaveDTO;
import com.mall.dto.SeckillExecuteDTO;
import com.mall.dto.SeckillPathDTO;
import com.mall.dto.SeckillSessionSaveDTO;
import com.mall.dto.SeckillSkuSaveDTO;
import com.mall.entity.OmsOrder;
import com.mall.entity.OmsOrderItem;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.entity.SmsSeckillActivity;
import com.mall.entity.SmsSeckillOrder;
import com.mall.entity.SmsSeckillSession;
import com.mall.entity.SmsSeckillSku;
import com.mall.mapper.OmsOrderItemMapper;
import com.mall.mapper.OmsOrderMapper;
import com.mall.mapper.PmsSkuMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.mapper.SmsSeckillActivityMapper;
import com.mall.mapper.SmsSeckillOrderMapper;
import com.mall.mapper.SmsSeckillSessionMapper;
import com.mall.mapper.SmsSeckillSkuMapper;
import com.mall.mapper.MqConsumeLogMapper;
import com.mall.model.SeckillSkuCache;
import com.mall.mq.OrderDelaySender;
import com.mall.mq.SeckillOrderSender;
import com.mall.mq.message.OrderDelayMessage;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.metrics.SeckillMetrics;
import com.mall.security.SecurityUtils;
import io.micrometer.core.instrument.Timer;
import com.mall.service.CaptchaService;
import com.mall.service.SeckillService;
import com.mall.vo.SeckillResultVO;
import com.mall.vo.SeckillSessionVO;
import com.mall.vo.SeckillSkuVO;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 秒杀服务实现
 * <p>
 * 流量漏斗（层层拦截，越靠前的闸门成本越低）：
 * <pre>
 * 请求 -> 0.图形验证码 + 动态 MD5 路径 + 滑动窗口限流（防机器刷/防接口探测/防连点）
 *      -> 1.本地内存售罄标记（0 网络 IO，命中直接拒绝）
 *      -> 2.Redis 缓存校验 + Lua 原子预减库存/一人一单判重（1 次 Redis 往返）
 *      -> 3.MQ 异步下单（削峰填谷，接口立即返回排队中）
 *      -> 4.消费端 Redisson 锁串行化同人请求 + DB 条件扣减（最终防线）+ 订单落库
 *      -> 5.事务提交后写结果 key，前端轮询感知成败
 * </pre>
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SmsSeckillActivityMapper activityMapper;
    private final SmsSeckillSessionMapper sessionMapper;
    private final SmsSeckillSkuMapper seckillSkuMapper;
    private final SmsSeckillOrderMapper seckillOrderMapper;
    private final MqConsumeLogMapper mqConsumeLogMapper;
    private final PmsSkuMapper skuMapper;
    private final PmsSpuMapper spuMapper;
    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    /**
     * 秒杀计数/集合/结果类 key 统一用 String 模板：Lua 参数必须是原始字符串，
     * JSON 序列化模板会带上引号导致 tonumber 失败
     */
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final SeckillProperties seckillProperties;
    private final OrderProperties orderProperties;
    private final SeckillOrderSender seckillOrderSender;
    private final OrderDelaySender orderDelaySender;
    private final CaptchaService captchaService;
    private final SeckillMetrics seckillMetrics;
    /**
     * 本地一级缓存（Caffeine）：缓存秒杀场次列表、场次 SKU 列表等读多写少的热数据，
     * 命中后零网络 IO，降低 Redis 带宽。与 PmsSpuServiceImpl 共享同一个 localCache Bean。
     */
    private final Cache<String, Object> localCache;

    /**
     * 本地内存售罄标记：seckillSkuId -> true。
     * 库存抢空后置位，后续请求直接在应用内存层拒绝，不再消耗 Redis 带宽；
     * 重新预热或库存回补时清除。
     */
    private final ConcurrentHashMap<Long, Boolean> localSoldOutMap = new ConcurrentHashMap<>();

    /**
     * Redis 预减库存 Lua（原子性保证不超卖）：
     * KEYS[1]=库存计数, KEYS[2]=已购集合；ARGV[1]=购买数量, ARGV[2]=用户ID
     * 返回 -1 重复购买 / 0 库存不足 / 1 预减成功（计数减 1 + 用户加入已购集合）
     */
    private static final DefaultRedisScript<Long> PRE_DEDUCT_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if redis.call('sismember', KEYS[2], ARGV[2]) == 1 then " +
                "    return -1 " +
                "end " +
                "local stock = tonumber(redis.call('get', KEYS[1])) " +
                "if stock == nil or stock < tonumber(ARGV[1]) then " +
                "    return 0 " +
                "end " +
                "redis.call('decrby', KEYS[1], ARGV[1]) " +
                "redis.call('sadd', KEYS[2], ARGV[2]) " +
                "return 1");
        script.setResultType(Long.class);
        PRE_DEDUCT_SCRIPT = script;
    }

    // ============================== 用户端 ==============================

    @Override
    public List<SeckillSessionVO> listSessions(LocalDate date) {
        String cacheKey = CacheConstants.SECKILL_SESSIONS_CACHE_KEY + date;

        // 0. 本地一级缓存（Caffeine）：场次列表读多写少，命中即返回
        Object local = localCache.getIfPresent(cacheKey);
        if (local instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(o -> (SeckillSessionVO) o).toList();
        }

        // 1. 二级缓存（Redis）
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof List<?> list && !list.isEmpty()) {
            List<SeckillSessionVO> voList = list.stream().map(o -> (SeckillSessionVO) o).toList();
            // Redis 命中后回填本地缓存
            localCache.put(cacheKey, voList);
            return voList;
        }

        // 2. 回源 DB
        LocalDateTime dayStart = date.atStartOfDay();
        List<SmsSeckillSession> sessions = sessionMapper.selectList(
                Wrappers.<SmsSeckillSession>lambdaQuery()
                        .eq(SmsSeckillSession::getStatus, 1)
                        .ge(SmsSeckillSession::getStartTime, dayStart)
                        .lt(SmsSeckillSession::getStartTime, dayStart.plusDays(1))
                        .orderByAsc(SmsSeckillSession::getStartTime));
        List<SeckillSessionVO> voList = sessions.stream().map(this::toSessionVO).toList();

        // 3. 写回 L2 Redis + L1 Caffeine
        redisTemplate.opsForValue().set(cacheKey, voList,
                seckillProperties.getSessionsCacheTtlSeconds(), TimeUnit.SECONDS);
        localCache.put(cacheKey, voList);
        return voList;
    }

    @Override
    public List<SeckillSkuVO> listSessionSkus(Long sessionId) {
        // Caffeine L1 key：场次 ID -> SKU 列表
        String localKey = "seckill:skus:list:" + sessionId;
        Object local = localCache.getIfPresent(localKey);
        if (local instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(o -> (SeckillSkuVO) o).toList();
        }

        // 已预热：读 Redis Hash（含开抢时间/价格快照）
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(
                CacheConstants.SECKILL_SKUS_HASH_KEY + sessionId);
        if (!entries.isEmpty()) {
            List<SeckillSkuVO> voList = new ArrayList<>();
            for (Object value : entries.values()) {
                SeckillSkuCache cache = (SeckillSkuCache) value;
                SeckillSkuVO vo = new SeckillSkuVO();
                vo.setId(cache.getSeckillSkuId());
                vo.setSessionId(cache.getSessionId());
                vo.setSkuId(cache.getSkuId());
                vo.setSpuName(cache.getSpuName());
                vo.setSkuName(cache.getSkuName());
                vo.setImage(cache.getImage());
                vo.setOriginalPrice(cache.getOriginalPrice());
                vo.setSeckillPrice(cache.getSeckillPrice());
                vo.setStockRemaining(loadStockRemaining(cache.getSeckillSkuId(), null));
                vo.setMaxPerUser(cache.getMaxPerUser());
                vo.setStartTime(cache.getStartTime());
                vo.setEndTime(cache.getEndTime());
                voList.add(vo);
            }
            // Redis 命中后回填本地缓存
            localCache.put(localKey, voList);
            return voList;
        }

        // 未预热：DB 兜底展示
        List<SmsSeckillSku> seckillSkus = seckillSkuMapper.selectList(
                Wrappers.<SmsSeckillSku>lambdaQuery().eq(SmsSeckillSku::getSessionId, sessionId));
        if (seckillSkus.isEmpty()) {
            return List.of();
        }
        Set<Long> skuIds = seckillSkus.stream().map(SmsSeckillSku::getSkuId).collect(Collectors.toSet());
        Map<Long, PmsSku> skuMap = skuMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(PmsSku::getId, Function.identity()));
        Set<Long> spuIds = skuMap.values().stream().map(PmsSku::getSpuId).collect(Collectors.toSet());
        Map<Long, PmsSpu> spuMap = spuIds.isEmpty() ? Map.of()
                : spuMapper.selectBatchIds(spuIds).stream()
                .collect(Collectors.toMap(PmsSpu::getId, Function.identity()));

        return seckillSkus.stream().map(ss -> {
            PmsSku sku = skuMap.get(ss.getSkuId());
            PmsSpu spu = sku == null ? null : spuMap.get(sku.getSpuId());
            SmsSeckillSession session = sessionMapper.selectById(ss.getSessionId());
            SeckillSkuVO vo = new SeckillSkuVO();
            vo.setId(ss.getId());
            vo.setSessionId(ss.getSessionId());
            vo.setSkuId(ss.getSkuId());
            vo.setSpuName(spu == null ? null : spu.getName());
            vo.setSkuName(sku == null ? null : sku.getName());
            vo.setImage(sku == null ? null : sku.getImage());
            vo.setOriginalPrice(sku == null ? null : sku.getPrice());
            vo.setSeckillPrice(ss.getSeckillPrice());
            vo.setStockRemaining(loadStockRemaining(ss.getId(), ss));
            vo.setMaxPerUser(ss.getMaxPerUser());
            if (session != null) {
                vo.setStartTime(session.getStartTime());
                vo.setEndTime(session.getEndTime());
            }
            return vo;
        }).toList();
    }

    @Override
    public String createSeckillPath(Long seckillSkuId, SeckillPathDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 1. 验证码一次性消费：错误/过期直接拒绝（机器刷单在最前端被拦截）；压测环境可关闭
        if (seckillProperties.isCaptchaEnabled()) {
            captchaService.verifyAndConsume(dto.getCaptchaToken(), dto.getCaptchaCode());
        }

        // 2. 商品必须已预热，且处于秒杀时间窗口内，开抢前不下发真实下单地址
        SeckillSkuCache cache = loadCache(seckillSkuId);
        if (cache == null) {
            throw new BusinessException("秒杀商品不存在或未上架");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(cache.getStartTime()) || now.isAfter(cache.getEndTime())) {
            throw new BusinessException("不在秒杀时间范围内，开抢时间：" + cache.getStartTime());
        }

        // 3. 生成用户+商品维度的确定性 MD5 路径，短 TTL 存 Redis；execute 时按值比对
        String path = DigestUtils.md5DigestAsHex(
                (userId + "_" + seckillSkuId + "_" + seckillProperties.getPathSalt())
                        .getBytes(StandardCharsets.UTF_8));
        stringRedisTemplate.opsForValue().set(pathKey(userId, seckillSkuId), path,
                seckillProperties.getPathTtlSeconds(), TimeUnit.SECONDS);
        return path;
    }

    @Override
    public SeckillResultVO execute(Long seckillSkuId, String path, SeckillExecuteDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 指标采样：无论从哪个出口返回/抛异常，finally 都记录出口计数与耗时
        Timer.Sample sample = seckillMetrics.startTimer();
        String outcome = SeckillMetrics.EXECUTE_ERROR;
        try {
            // 0. 动态路径校验：未通过验证码换取路径/路径过期/伪造路径一律拦截
            String savedPath = stringRedisTemplate.opsForValue().get(pathKey(userId, seckillSkuId));
            if (savedPath == null || !savedPath.equals(path)) {
                outcome = SeckillMetrics.EXECUTE_INVALID_PATH;
                throw new BusinessException("秒杀地址无效或已过期，请重新获取");
            }

            // 1. 本地内存售罄标记：命中直接拒绝，0 网络 IO
            if (seckillProperties.isLocalMarkEnabled()
                    && Boolean.TRUE.equals(localSoldOutMap.get(seckillSkuId))) {
                outcome = SeckillMetrics.EXECUTE_SOLD_OUT_LOCAL;
                throw new BusinessException("已抢光，下次早点来~");
            }

            // 2. 秒杀商品信息全部来自预热缓存（应用不打商品库），校验时间窗口
            SeckillSkuCache cache = loadCache(seckillSkuId);
            if (cache == null) {
                outcome = SeckillMetrics.EXECUTE_INVALID;
                throw new BusinessException("秒杀商品不存在或未上架");
            }
            LocalDateTime now = LocalDateTime.now();
            if (now.isBefore(cache.getStartTime()) || now.isAfter(cache.getEndTime())) {
                outcome = SeckillMetrics.EXECUTE_INVALID;
                throw new BusinessException("不在秒杀时间范围内，开抢时间：" + cache.getStartTime());
            }

            // 3. Lua 原子操作：一人一单判重 + 库存预减（-1 重复 / 0 抢空 / 1 成功）
            Long result = stringRedisTemplate.execute(PRE_DEDUCT_SCRIPT,
                    List.of(stockKey(seckillSkuId), orderedSetKey(seckillSkuId)),
                    "1", String.valueOf(userId));
            if (result == null || result == 0L) {
                // 售罄置本地标记，后续同类请求不再打 Redis
                localSoldOutMap.put(seckillSkuId, Boolean.TRUE);
                outcome = SeckillMetrics.EXECUTE_SOLD_OUT;
                throw new BusinessException("已抢光，下次早点来~");
            }
            if (result == -1L) {
                outcome = SeckillMetrics.EXECUTE_REPEATED;
                throw new BusinessException("您已抢购过该商品，请勿重复抢购");
            }

            // 清除上次抢购的结果 key，避免前端轮询读到旧结果
            stringRedisTemplate.delete(resultKey(userId, seckillSkuId));

            // 4. MQ 异步下单（削峰），接口立即返回排队中，前端轮询结果
            seckillOrderSender.sendSeckillOrder(new SeckillOrderMessage(userId, seckillSkuId,
                    dto.getReceiverName(), dto.getReceiverPhone(), dto.getReceiverAddress(), dto.getRemark()));

            SeckillResultVO vo = new SeckillResultVO();
            vo.setStatus(SeckillResultVO.STATUS_QUEUING);
            outcome = SeckillMetrics.EXECUTE_QUEUED;
            return vo;
        } finally {
            seckillMetrics.recordExecute(sample, outcome);
        }
    }

    @Override
    public SeckillResultVO result(Long seckillSkuId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String value = stringRedisTemplate.opsForValue().get(resultKey(userId, seckillSkuId));

        SeckillResultVO vo = new SeckillResultVO();
        if (value == null) {
            vo.setStatus(SeckillResultVO.STATUS_QUEUING);
        } else if (CacheConstants.SECKILL_RESULT_FAIL.equals(value)) {
            vo.setStatus(SeckillResultVO.STATUS_FAIL);
        } else {
            vo.setStatus(SeckillResultVO.STATUS_SUCCESS);
            vo.setOrderId(Long.valueOf(value.toString()));
        }
        return vo;
    }

    // ============================== MQ 消费 ==============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrder(SeckillOrderMessage message) {
        // MQ 消费幂等：INSERT IGNORE + message_id 唯一索引，同一条消息只落单一次。
        // 与本方法同事务：业务成功提交则日志留存，后续重投被拦截；业务失败回滚则日志一并回滚，允许重试。
        if (mqConsumeLogMapper.tryConsume(message.getMessageId(), MqConstants.SECKILL_ORDER_QUEUE) == 0) {
            log.info("秒杀消息已消费，幂等跳过, messageId={}, userId={}, seckillSkuId={}",
                    message.getMessageId(), message.getUserId(), message.getSeckillSkuId());
            seckillMetrics.recordConsume(SeckillMetrics.CONSUME_DUPLICATE);
            return;
        }

        Long userId = message.getUserId();
        Long seckillSkuId = message.getSeckillSkuId();

        // Redisson 锁串行化同一用户对同一秒杀商品的并发消息（一人一单业务层保障）
        RLock lock = redissonClient.getLock(
                CacheConstants.SECKILL_LOCK_KEY + userId + ":" + seckillSkuId);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }
            doHandleSeckillOrder(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void doHandleSeckillOrder(SeckillOrderMessage message) {
        Long userId = message.getUserId();
        Long seckillSkuId = message.getSeckillSkuId();

        // 幂等：MQ 重复投递时该用户已存在秒杀订单，直接跳过（DB 唯一索引最终兜底）
        Long exists = seckillOrderMapper.selectCount(Wrappers.<SmsSeckillOrder>lambdaQuery()
                .eq(SmsSeckillOrder::getUserId, userId)
                .eq(SmsSeckillOrder::getSeckillSkuId, seckillSkuId));
        if (exists != null && exists > 0) {
            log.info("秒杀订单已存在，跳过重复消息, userId={}, seckillSkuId={}", userId, seckillSkuId);
            seckillMetrics.recordConsume(SeckillMetrics.CONSUME_DUPLICATE);
            return;
        }

        SeckillSkuCache cache = loadCache(seckillSkuId);
        if (cache == null) {
            throw new BusinessException("秒杀商品缓存缺失，无法落单");
        }
        PmsSku sku = skuMapper.selectById(cache.getSkuId());
        PmsSpu spu = sku == null ? null : spuMapper.selectById(sku.getSpuId());
        if (sku == null || spu == null) {
            throw new BusinessException("商品信息缺失，无法落单");
        }

        // DB 条件扣减秒杀库存（Redis 与 DB 不一致时的最终防线，防超卖）
        int rows = seckillSkuMapper.deductSeckillStock(seckillSkuId, 1);
        if (rows == 0) {
            throw new BusinessException("秒杀库存不足");
        }

        // 生成真实订单（order_type=2），价格/商品名均为秒杀价快照
        OmsOrder order = new OmsOrder();
        order.setOrderSn("SK" + IdWorker.getIdStr());
        order.setUserId(userId);
        order.setTotalAmount(cache.getSeckillPrice());
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setOrderType(2);
        order.setReceiverName(message.getReceiverName());
        order.setReceiverPhone(message.getReceiverPhone());
        order.setReceiverAddress(message.getReceiverAddress());
        order.setRemark(message.getRemark());
        order.setExpireTime(LocalDateTime.now().plusSeconds(orderProperties.getTimeoutSeconds()));
        orderMapper.insert(order);

        OmsOrderItem item = new OmsOrderItem();
        item.setOrderId(order.getId());
        item.setOrderSn(order.getOrderSn());
        item.setUserId(userId);
        item.setSkuId(sku.getId());
        item.setSpuId(sku.getSpuId());
        item.setSpuName(cache.getSpuName());
        item.setSkuName(cache.getSkuName());
        item.setSpecText(SpecTextUtil.toText(sku.getSpecJson()));
        item.setSkuImage(cache.getImage());
        item.setPrice(cache.getSeckillPrice());
        item.setQuantity(1);
        item.setTotalPrice(cache.getSeckillPrice());
        orderItemMapper.insert(item);

        SmsSeckillOrder seckillOrder = new SmsSeckillOrder();
        seckillOrder.setSeckillSkuId(seckillSkuId);
        seckillOrder.setSessionId(cache.getSessionId());
        seckillOrder.setUserId(userId);
        seckillOrder.setOrderId(order.getId());
        seckillOrder.setOrderSn(order.getOrderSn());
        seckillOrder.setSeckillPrice(cache.getSeckillPrice());
        seckillOrder.setQuantity(1);
        seckillOrderMapper.insert(seckillOrder);

        // 事务提交成功后：发延迟关单消息 + 写结果 key（前端轮询感知成功）
        registerAfterCommit(order.getId(), order.getOrderSn(), message);
        log.info("秒杀下单成功, userId={}, seckillSkuId={}, orderSn={}", userId, seckillSkuId, order.getOrderSn());
    }

    @Override
    public void compensateOnConsumeFailure(SeckillOrderMessage message) {
        Long userId = message.getUserId();
        Long seckillSkuId = message.getSeckillSkuId();
        try {
            // 已成功落单（重复投递的失败消息）不补偿，避免库存虚增
            Long exists = seckillOrderMapper.selectCount(Wrappers.<SmsSeckillOrder>lambdaQuery()
                    .eq(SmsSeckillOrder::getUserId, userId)
                    .eq(SmsSeckillOrder::getSeckillSkuId, seckillSkuId));
            if (exists != null && exists > 0) {
                return;
            }
            stringRedisTemplate.opsForValue().increment(stockKey(seckillSkuId), 1);
            stringRedisTemplate.opsForSet().remove(orderedSetKey(seckillSkuId), String.valueOf(userId));
            stringRedisTemplate.opsForValue().set(resultKey(userId, seckillSkuId), CacheConstants.SECKILL_RESULT_FAIL,
                    seckillProperties.getResultTtlSeconds(), TimeUnit.SECONDS);
            localSoldOutMap.remove(seckillSkuId);
            log.info("秒杀消费失败已补偿 Redis 库存, userId={}, seckillSkuId={}", userId, seckillSkuId);
        } catch (Exception ex) {
            log.error("秒杀失败补偿异常, userId={}, seckillSkuId={}", userId, seckillSkuId, ex);
        }
    }

    // ============================== 管理端 ==============================

    @Override
    public void createActivity(SeckillActivitySaveDTO dto) {
        SmsSeckillActivity activity = new SmsSeckillActivity();
        activity.setName(dto.getName());
        activity.setStatus(1);
        activityMapper.insert(activity);
    }

    @Override
    public List<SmsSeckillActivity> listActivities() {
        return activityMapper.selectList(Wrappers.<SmsSeckillActivity>lambdaQuery().orderByDesc(SmsSeckillActivity::getId));
    }

    @Override
    public void createSession(SeckillSessionSaveDTO dto) {
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException("结束时间必须晚于开抢时间");
        }
        SmsSeckillActivity activity = activityMapper.selectById(dto.getActivityId());
        if (activity == null || !Objects.equals(activity.getStatus(), 1)) {
            throw new BusinessException("活动不存在或已下线");
        }
        SmsSeckillSession session = new SmsSeckillSession();
        session.setActivityId(dto.getActivityId());
        session.setName(dto.getName());
        session.setStartTime(dto.getStartTime());
        session.setEndTime(dto.getEndTime());
        session.setStatus(1);
        sessionMapper.insert(session);
    }

    @Override
    public void createSeckillSku(SeckillSkuSaveDTO dto) {
        SmsSeckillSession session = sessionMapper.selectById(dto.getSessionId());
        if (session == null || !Objects.equals(session.getStatus(), 1)) {
            throw new BusinessException("场次不存在或未启用");
        }
        PmsSku sku = skuMapper.selectById(dto.getSkuId());
        if (sku == null || !Objects.equals(sku.getStatus(), 1)) {
            throw new BusinessException("商品 SKU 不存在或未上架");
        }
        PmsSpu spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null || !Objects.equals(spu.getStatus(), 1)) {
            throw new BusinessException("商品 SPU 不存在或未上架");
        }
        if (dto.getSeckillPrice().compareTo(sku.getPrice()) >= 0) {
            log.warn("秒杀价未低于原价, seckillPrice={}, originalPrice={}", dto.getSeckillPrice(), sku.getPrice());
        }
        Long dup = seckillSkuMapper.selectCount(Wrappers.<SmsSeckillSku>lambdaQuery()
                .eq(SmsSeckillSku::getSessionId, dto.getSessionId())
                .eq(SmsSeckillSku::getSkuId, dto.getSkuId()));
        if (dup != null && dup > 0) {
            throw new BusinessException("该场次已包含此商品");
        }

        SmsSeckillSku seckillSku = new SmsSeckillSku();
        seckillSku.setActivityId(session.getActivityId());
        seckillSku.setSessionId(dto.getSessionId());
        seckillSku.setSkuId(dto.getSkuId());
        seckillSku.setSeckillPrice(dto.getSeckillPrice());
        seckillSku.setSeckillStock(dto.getSeckillStock());
        seckillSku.setSeckillCount(0);
        seckillSku.setMaxPerUser(1);
        seckillSkuMapper.insert(seckillSku);
    }

    @Override
    public void uploadToRedis(Long seckillSkuId) {
        SmsSeckillSku ss = seckillSkuMapper.selectById(seckillSkuId);
        if (ss == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "秒杀商品不存在");
        }
        SmsSeckillSession session = sessionMapper.selectById(ss.getSessionId());
        PmsSku sku = skuMapper.selectById(ss.getSkuId());
        PmsSpu spu = sku == null ? null : spuMapper.selectById(sku.getSpuId());
        if (session == null || sku == null || spu == null) {
            throw new BusinessException("场次/商品数据不完整，无法上架");
        }
        if (!Objects.equals(session.getStatus(), 1) || !Objects.equals(sku.getStatus(), 1)
                || !Objects.equals(spu.getStatus(), 1)) {
            throw new BusinessException("场次或商品未启用，无法上架");
        }

        SeckillSkuCache cache = new SeckillSkuCache();
        cache.setSeckillSkuId(ss.getId());
        cache.setSessionId(ss.getSessionId());
        cache.setSkuId(ss.getSkuId());
        cache.setSpuId(spu.getId());
        cache.setSpuName(spu.getName());
        cache.setSkuName(sku.getName());
        cache.setImage(sku.getImage());
        cache.setOriginalPrice(sku.getPrice());
        cache.setSeckillPrice(ss.getSeckillPrice());
        cache.setSeckillStock(ss.getSeckillStock());
        cache.setMaxPerUser(ss.getMaxPerUser());
        cache.setStartTime(session.getStartTime());
        cache.setEndTime(session.getEndTime());

        // 剩余可抢数量（重新上架不重置已售部分）
        int remaining = ss.getSeckillStock() - (ss.getSeckillCount() == null ? 0 : ss.getSeckillCount());
        stringRedisTemplate.opsForValue().set(stockKey(ss.getId()), String.valueOf(remaining));
        stringRedisTemplate.opsForValue().set(CacheConstants.SECKILL_SKU_SESSION_KEY + ss.getId(), String.valueOf(ss.getSessionId()));
        redisTemplate.opsForHash().put(CacheConstants.SECKILL_SKUS_HASH_KEY + ss.getSessionId(),
                String.valueOf(ss.getId()), cache);
        // 清除本地售罄标记，允许继续抢购
        localSoldOutMap.remove(ss.getId());
        // 失效该场次的 SKU 列表本地缓存（Cache Aside），下次查询触发重建
        localCache.invalidate("seckill:skus:list:" + ss.getSessionId());
        log.info("秒杀商品预热完成, seckillSkuId={}, sessionId={}, remaining={}", ss.getId(), ss.getSessionId(), remaining);
    }

    // ============================== 订单联动 ==============================

    @Override
    public void restoreAfterOrderCancel(Long orderId) {
        SmsSeckillOrder seckillOrder = seckillOrderMapper.selectOne(
                Wrappers.<SmsSeckillOrder>lambdaQuery().eq(SmsSeckillOrder::getOrderId, orderId).last("LIMIT 1"));
        if (seckillOrder == null) {
            // 非秒杀订单，交由调用方走普通库存回补
            return;
        }
        int quantity = seckillOrder.getQuantity() == null ? 1 : seckillOrder.getQuantity();
        seckillSkuMapper.restoreSeckillStock(seckillOrder.getSeckillSkuId(), quantity);
        // 删除秒杀订单关联记录：该表只承载有效抢购关系（uk_user_sku 唯一约束），
        // 不删除会导致取消后复购被消费端判重拦截、唯一索引插入失败
        seckillOrderMapper.deleteById(seckillOrder.getId());
        stringRedisTemplate.opsForValue().increment(stockKey(seckillOrder.getSeckillSkuId()), quantity);
        stringRedisTemplate.opsForSet().remove(orderedSetKey(seckillOrder.getSeckillSkuId()),
                String.valueOf(seckillOrder.getUserId()));
        // 库存回补后清除售罄标记，允许继续抢购
        localSoldOutMap.remove(seckillOrder.getSeckillSkuId());
        log.info("秒杀订单取消回补库存, orderId={}, seckillSkuId={}, quantity={}",
                orderId, seckillOrder.getSeckillSkuId(), quantity);
    }

    // ============================== 私有方法 ==============================

    /**
     * 从预热缓存加载秒杀商品信息；执行路径需要先用索引定位场次
     */
    private SeckillSkuCache loadCache(Long seckillSkuId) {
        String sessionId = stringRedisTemplate.opsForValue()
                .get(CacheConstants.SECKILL_SKU_SESSION_KEY + seckillSkuId);
        if (sessionId == null) {
            return null;
        }
        Object cache = redisTemplate.opsForHash().get(CacheConstants.SECKILL_SKUS_HASH_KEY + sessionId,
                String.valueOf(seckillSkuId));
        return cache instanceof SeckillSkuCache c ? c : null;
    }

    /**
     * 剩余可抢数量：预热后以 Redis 计数为准，否则用 DB 秒杀库存减已售
     */
    private Integer loadStockRemaining(Long seckillSkuId, SmsSeckillSku fallback) {
        String stock = stringRedisTemplate.opsForValue().get(stockKey(seckillSkuId));
        if (stock != null) {
            try {
                return Integer.parseInt(stock);
            } catch (NumberFormatException ignored) {
                // 落到 DB 兜底
            }
        }
        if (fallback != null) {
            int sold = fallback.getSeckillCount() == null ? 0 : fallback.getSeckillCount();
            int total = fallback.getSeckillStock() == null ? 0 : fallback.getSeckillStock();
            return Math.max(total - sold, 0);
        }
        SmsSeckillSku ss = seckillSkuMapper.selectById(seckillSkuId);
        if (ss == null) {
            return 0;
        }
        int sold = ss.getSeckillCount() == null ? 0 : ss.getSeckillCount();
        return Math.max(ss.getSeckillStock() - sold, 0);
    }

    private SeckillSessionVO toSessionVO(SmsSeckillSession session) {
        SeckillSessionVO vo = new SeckillSessionVO();
        vo.setId(session.getId());
        vo.setName(session.getName());
        vo.setStartTime(session.getStartTime());
        vo.setEndTime(session.getEndTime());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(session.getStartTime())) {
            vo.setStatusDesc("未开始");
        } else if (now.isAfter(session.getEndTime())) {
            vo.setStatusDesc("已结束");
        } else {
            vo.setStatusDesc("进行中");
        }
        return vo;
    }

    /**
     * 事务提交成功后再发延迟关单消息与结果 key，避免「事务回滚但用户已看到成功」
     */
    private void registerAfterCommit(Long orderId, String orderSn, SeckillOrderMessage message) {
        Runnable afterCommitTask = () -> {
            orderDelaySender.sendDelayClose(new OrderDelayMessage(orderId, orderSn));
            stringRedisTemplate.opsForValue().set(resultKey(message.getUserId(), message.getSeckillSkuId()),
                    String.valueOf(orderId), seckillProperties.getResultTtlSeconds(), TimeUnit.SECONDS);
            // 仅事务真正提交后才计成功，避免回滚造成指标虚高
            seckillMetrics.recordConsume(SeckillMetrics.CONSUME_SUCCESS);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCommitTask.run();
                }
            });
        } else {
            afterCommitTask.run();
        }
    }

    private String stockKey(Long seckillSkuId) {
        return CacheConstants.SECKILL_STOCK_KEY + seckillSkuId;
    }

    private String orderedSetKey(Long seckillSkuId) {
        return CacheConstants.SECKILL_ORDERED_SET_KEY + seckillSkuId;
    }

    private String resultKey(Long userId, Long seckillSkuId) {
        return CacheConstants.SECKILL_RESULT_KEY + userId + ":" + seckillSkuId;
    }

    private String pathKey(Long userId, Long seckillSkuId) {
        return CacheConstants.SECKILL_PATH_KEY + userId + ":" + seckillSkuId;
    }
}
