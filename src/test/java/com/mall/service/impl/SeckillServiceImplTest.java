package com.mall.service.impl;

import com.mall.TestSecurityHelper;
import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.config.OrderProperties;
import com.mall.config.SeckillProperties;
import com.mall.dto.SeckillExecuteDTO;
import com.mall.dto.SeckillPathDTO;
import com.mall.entity.SmsSeckillOrder;
import com.mall.mapper.SmsSeckillOrderMapper;
import com.mall.metrics.SeckillMetrics;
import com.mall.model.SeckillSkuCache;
import com.mall.mq.OrderDelaySender;
import com.mall.mq.SeckillOrderSender;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.security.SecurityUtils;
import com.mall.service.CaptchaService;
import com.mall.vo.SeckillResultVO;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀服务单元测试：覆盖 execute 六级流量漏斗出口、动态路径、结果轮询、
 * MQ 消费幂等与失败补偿、管理端校验。所有 DB/Redis/MQ 依赖均 Mock。
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock
    private SmsSeckillOrderMapper seckillOrderMapper;
    @Mock
    private com.mall.mapper.MqConsumeLogMapper mqConsumeLogMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private org.springframework.data.redis.core.SetOperations<String, String> setOps;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    @SuppressWarnings("rawtypes")
    private HashOperations hashOps;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SeckillProperties seckillProperties;
    @Mock
    private OrderProperties orderProperties;
    @Mock
    private SeckillOrderSender seckillOrderSender;
    @Mock
    private OrderDelaySender orderDelaySender;
    @Mock
    private CaptchaService captchaService;
    @Mock
    private SeckillMetrics seckillMetrics;
    @Mock
    private com.github.benmanes.caffeine.cache.Cache<String, Object> localCache;

    private SeckillServiceImpl seckillService;

    private static final Long USER_ID = 1001L;
    private static final Long SKU_ID = 99L;

    @BeforeEach
    void setUp() {
        TestSecurityHelper.loginAs(USER_ID);
        // 手动构造：StringRedisTemplate extends RedisTemplate，@InjectMocks 按类型注入会错位
        seckillService = new SeckillServiceImpl(
                null, null, null, seckillOrderMapper, mqConsumeLogMapper, null, null, null, null,
                redisTemplate, stringRedisTemplate, redissonClient, seckillProperties,
                orderProperties, seckillOrderSender, orderDelaySender, captchaService, seckillMetrics, localCache);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        // 默认本地售罄标记开启
        lenient().when(seckillProperties.isLocalMarkEnabled()).thenReturn(true);
        // 指标 Timer.Sample 桩件
        lenient().when(seckillMetrics.startTimer()).thenReturn(org.mockito.Mockito.mock(Timer.Sample.class));
    }

    @AfterEach
    void tearDown() {
        TestSecurityHelper.clear();
    }

    // ============================ execute 六级漏斗 ============================

    @Test
    @DisplayName("execute-0 动态路径不匹配 -> 拒绝")
    void execute_invalidPath() {
        when(valueOps.get(pathKey())).thenReturn("real-path");

        SeckillExecuteDTO dto = new SeckillExecuteDTO();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, "forged-path", dto));
        assertEquals("秒杀地址无效或已过期，请重新获取", ex.getMessage());
        // 路径校验失败，不应发送 MQ
        verify(seckillOrderSender, never()).sendSeckillOrder(any());
    }

    @Test
    @DisplayName("execute-1 本地售罄标记命中 -> 直接拒绝，0 网络 IO")
    void execute_soldOutLocal() {
        when(valueOps.get(pathKey())).thenReturn("valid-path");
        // 先打一次让 Lua 返回 0，触发本地售罄标记置位
        when(valueOps.get(sessionKey())).thenReturn("5");
        when(hashOps.get(anyString(), anyString())).thenReturn(validCache());
        when(stringRedisTemplate.execute(any(), anyList(), anyString(), anyString()))
                .thenReturn(0L);

        SeckillExecuteDTO dto = new SeckillExecuteDTO();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, "valid-path", dto));
        assertEquals("已抢光，下次早点来~", ex.getMessage());

        // 第二次请求：本地标记命中，不再执行 Lua
        assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, "valid-path", dto));
        verify(stringRedisTemplate, times(1)).execute(any(), anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("execute-2 商品未预热（缓存缺失） -> 拒绝")
    void execute_invalidNoCache() {
        when(valueOps.get(pathKey())).thenReturn("valid-path");
        when(valueOps.get(sessionKey())).thenReturn(null); // 未预热

        SeckillExecuteDTO dto = new SeckillExecuteDTO();
        assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, "valid-path", dto),
                "秒杀商品不存在或未上架");
    }

    @Test
    @DisplayName("execute-3 Lua 返回 0 库存不足 -> 拒绝并置本地售罄标记")
    void execute_soldOut() {
        when(valueOps.get(pathKey())).thenReturn("valid-path");
        when(valueOps.get(sessionKey())).thenReturn("5");
        when(hashOps.get(anyString(), anyString())).thenReturn(validCache());
        when(stringRedisTemplate.execute(any(), anyList(), anyString(), anyString()))
                .thenReturn(0L);

        SeckillExecuteDTO dto = new SeckillExecuteDTO();
        assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, "valid-path", dto),
                "已抢光，下次早点来~");
    }

    @Test
    @DisplayName("execute-4 Lua 返回 -1 重复购买 -> 拒绝（一人一单）")
    void execute_repeated() {
        when(valueOps.get(pathKey())).thenReturn("valid-path");
        when(valueOps.get(sessionKey())).thenReturn("5");
        when(hashOps.get(anyString(), anyString())).thenReturn(validCache());
        when(stringRedisTemplate.execute(any(), anyList(), anyString(), anyString()))
                .thenReturn(-1L);

        SeckillExecuteDTO dto = new SeckillExecuteDTO();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, "valid-path", dto));
        assertEquals("您已抢购过该商品，请勿重复抢购", ex.getMessage());
        verify(seckillOrderSender, never()).sendSeckillOrder(any());
    }

    @Test
    @DisplayName("execute-5 Lua 返回 1 -> 发送 MQ，返回 QUEUING")
    void execute_queued() {
        when(valueOps.get(pathKey())).thenReturn("valid-path");
        when(valueOps.get(sessionKey())).thenReturn("5");
        when(hashOps.get(anyString(), anyString())).thenReturn(validCache());
        when(stringRedisTemplate.execute(any(), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        SeckillExecuteDTO dto = new SeckillExecuteDTO();
        dto.setReceiverName("张三");
        dto.setReceiverPhone("13800138000");
        dto.setReceiverAddress("地址");

        SeckillResultVO vo = seckillService.execute(SKU_ID, "valid-path", dto);
        assertEquals(SeckillResultVO.STATUS_QUEUING, vo.getStatus());

        verify(seckillOrderSender, times(1)).sendSeckillOrder(any(SeckillOrderMessage.class));
    }

    // ============================ 动态路径换取 ============================

    @Test
    @DisplayName("createSeckillPath 未预热 -> 拒绝")
    void createPath_notPreheated() {
        when(valueOps.get(sessionKey())).thenReturn(null);
        SeckillPathDTO dto = new SeckillPathDTO();
        assertThrows(BusinessException.class, () -> seckillService.createSeckillPath(SKU_ID, dto));
    }

    @Test
    @DisplayName("createSeckillPath 成功下发 MD5 路径并存 Redis")
    void createPath_success() {
        when(valueOps.get(sessionKey())).thenReturn("5");
        when(hashOps.get(anyString(), anyString())).thenReturn(validCache());
        when(seckillProperties.getPathSalt()).thenReturn("salt");
        when(seckillProperties.getPathTtlSeconds()).thenReturn(60L);

        SeckillPathDTO dto = new SeckillPathDTO();
        String path = seckillService.createSeckillPath(SKU_ID, dto);

        // path = MD5(userId_skuId_salt)
        String expected = org.springframework.util.DigestUtils.md5DigestAsHex(
                (USER_ID + "_" + SKU_ID + "_" + "salt").getBytes());
        assertEquals(expected, path);
        verify(valueOps).set(eq(pathKey()), eq(path), eq(60L), eq(TimeUnit.SECONDS));
    }

    // ============================ 结果轮询 ============================

    @Test
    @DisplayName("result 无结果 key -> QUEUING")
    void result_queuing() {
        when(valueOps.get(resultKey())).thenReturn(null);
        assertEquals(SeckillResultVO.STATUS_QUEUING, seckillService.result(SKU_ID).getStatus());
    }

    @Test
    @DisplayName("result FAIL -> FAIL")
    void result_fail() {
        when(valueOps.get(resultKey())).thenReturn(CacheConstants.SECKILL_RESULT_FAIL);
        assertEquals(SeckillResultVO.STATUS_FAIL, seckillService.result(SKU_ID).getStatus());
    }

    @Test
    @DisplayName("result 订单ID -> SUCCESS")
    void result_success() {
        when(valueOps.get(resultKey())).thenReturn("12345");
        SeckillResultVO vo = seckillService.result(SKU_ID);
        assertEquals(SeckillResultVO.STATUS_SUCCESS, vo.getStatus());
        assertEquals(12345L, vo.getOrderId());
    }

    // ============================ MQ 消费 ============================

    @Test
    @DisplayName("handleSeckillOrder 幂等：已存在秒杀订单直接跳过")
    void handleSeckillOrder_duplicate() {
        RLock lock = mockLock();
        when(mqConsumeLogMapper.tryConsume(any(), any())).thenReturn(1);
        when(seckillOrderMapper.selectCount(any())).thenReturn(1L);
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        SeckillOrderMessage msg = new SeckillOrderMessage(USER_ID, SKU_ID, "", "", "", null);
        seckillService.handleSeckillOrder(msg);

        verify(seckillMetrics, times(1)).recordConsume(SeckillMetrics.CONSUME_DUPLICATE);
    }

    @Test
    @DisplayName("compensateOnConsumeFailure 已落单不补偿；未落单归还 Redis 库存")
    void compensate_alreadyOrdered_skip() {
        when(seckillOrderMapper.selectCount(any())).thenReturn(1L);
        SeckillOrderMessage msg = new SeckillOrderMessage(USER_ID, SKU_ID, "", "", "", null);
        seckillService.compensateOnConsumeFailure(msg);
        // 已落单：不应 increment 库存
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    @DisplayName("compensateOnConsumeFailure 未落单 -> 归还库存并移除一人一单标记")
    void compensate_restoreStock() {
        when(seckillOrderMapper.selectCount(any())).thenReturn(0L);
        when(seckillProperties.getResultTtlSeconds()).thenReturn(600L);

        SeckillOrderMessage msg = new SeckillOrderMessage(USER_ID, SKU_ID, "", "", "", null);
        seckillService.compensateOnConsumeFailure(msg);

        verify(valueOps).increment(stockKey(), 1);
        verify(stringRedisTemplate.opsForSet()).remove(orderedSetKey(), String.valueOf(USER_ID));
        verify(valueOps).set(eq(resultKey()), eq(CacheConstants.SECKILL_RESULT_FAIL),
                eq(600L), eq(TimeUnit.SECONDS));
    }

    // ============================ 管理端校验 ============================

    @Test
    @DisplayName("createSession 结束时间不晚于开始时间 -> 拒绝")
    void createSession_invalidTime() {
        com.mall.dto.SeckillSessionSaveDTO dto = new com.mall.dto.SeckillSessionSaveDTO();
        dto.setStartTime(LocalDateTime.now());
        dto.setEndTime(LocalDateTime.now().minusHours(1));
        assertThrows(BusinessException.class, () -> seckillService.createSession(dto));
    }

    // ============================ 辅助方法 ============================

    private SeckillSkuCache validCache() {
        SeckillSkuCache cache = new SeckillSkuCache();
        cache.setSeckillSkuId(SKU_ID);
        cache.setSessionId(5L);
        cache.setSkuId(1L);
        cache.setStartTime(LocalDateTime.now().minusHours(1));
        cache.setEndTime(LocalDateTime.now().plusHours(1));
        return cache;
    }

    private RLock mockLock() {
        RLock lock = org.mockito.Mockito.mock(RLock.class);
        try {
            // 用字面量而非 matcher，避免 checked exception + matcher 组合破坏 Mockito 状态
            doReturn(true).when(lock).tryLock(3L, 10L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        doNothing().when(lock).unlock();
        return lock;
    }

    private String pathKey() {
        return CacheConstants.SECKILL_PATH_KEY + USER_ID + ":" + SKU_ID;
    }

    private String resultKey() {
        return CacheConstants.SECKILL_RESULT_KEY + USER_ID + ":" + SKU_ID;
    }

    private String sessionKey() {
        return CacheConstants.SECKILL_SKU_SESSION_KEY + SKU_ID;
    }

    private String stockKey() {
        return CacheConstants.SECKILL_STOCK_KEY + SKU_ID;
    }

    private String orderedSetKey() {
        return CacheConstants.SECKILL_ORDERED_SET_KEY + SKU_ID;
    }
}
