package com.mall.service.impl;

import com.mall.TestSecurityHelper;
import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.config.RedisConfig;
import com.mall.config.SeckillProperties;
import com.mall.metrics.SeckillMetrics;
import com.mall.model.SeckillSkuCache;
import com.mall.mq.OrderDelaySender;
import com.mall.mq.SeckillOrderSender;
import com.mall.service.CaptchaService;
import com.mall.vo.SeckillResultVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 秒杀集成测试：内嵌 Redis（JVM 内启动 Redis 协议服务，支持 Lua 脚本），
 * 验证秒杀漏斗核心链路在真实 Redis 上的执行效果：
 * Lua 原子预减库存、一人一单判重、库存耗尽售罄、结果轮询。
 * DB Mapper 与 MQ Sender 用 Mock 隔离。
 */
@SpringBootTest(classes = SeckillIntegrationTest.TestConfig.class)
class SeckillIntegrationTest {

    private static RedisServer redisServer;
    private static int redisPort;

    @BeforeAll
    static void startRedis() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            redisPort = ss.getLocalPort();
        }
        redisServer = new RedisServer(redisPort);
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
        }
    }

    @Autowired
    private SeckillServiceImpl seckillService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final Long USER_ID = 1001L;
    private static final Long SKU_ID = 555L;
    private static final Long SESSION_ID = 7L;

    @BeforeEach
    void setUp() {
        TestSecurityHelper.loginAs(USER_ID);
        stringRedisTemplate.opsForValue().set(
                CacheConstants.SECKILL_SKU_SESSION_KEY + SKU_ID, String.valueOf(SESSION_ID));
        stringRedisTemplate.opsForValue().set(
                CacheConstants.SECKILL_STOCK_KEY + SKU_ID, "10");
        SeckillSkuCache cache = new SeckillSkuCache();
        cache.setSeckillSkuId(SKU_ID);
        cache.setSessionId(SESSION_ID);
        cache.setSkuId(1L);
        cache.setStartTime(LocalDateTime.now().minusHours(1));
        cache.setEndTime(LocalDateTime.now().plusHours(1));
        redisTemplate.opsForHash().put(
                CacheConstants.SECKILL_SKUS_HASH_KEY + SESSION_ID,
                String.valueOf(SKU_ID), cache);
    }

    @AfterEach
    void tearDown() {
        TestSecurityHelper.clear();
        stringRedisTemplate.delete(CacheConstants.SECKILL_SKU_SESSION_KEY + SKU_ID);
        stringRedisTemplate.delete(CacheConstants.SECKILL_STOCK_KEY + SKU_ID);
        stringRedisTemplate.delete(CacheConstants.SECKILL_ORDERED_SET_KEY + SKU_ID);
        stringRedisTemplate.delete(CacheConstants.SECKILL_PATH_KEY + USER_ID + ":" + SKU_ID);
        stringRedisTemplate.delete(CacheConstants.SECKILL_RESULT_KEY + USER_ID + ":" + SKU_ID);
        redisTemplate.delete(CacheConstants.SECKILL_SKUS_HASH_KEY + SESSION_ID);
    }

    @Test
    @DisplayName("Lua 预减成功：库存 10->9，已购集合加入用户，返回 QUEUING")
    void execute_success_deductsStock() {
        String path = seckillService.createSeckillPath(SKU_ID, new com.mall.dto.SeckillPathDTO());
        assertNotNull(path);

        SeckillResultVO vo = seckillService.execute(SKU_ID, path, buildDto());
        assertEquals(SeckillResultVO.STATUS_QUEUING, vo.getStatus());

        assertEquals("9", stringRedisTemplate.opsForValue().get(CacheConstants.SECKILL_STOCK_KEY + SKU_ID));
        assertEquals(1L, stringRedisTemplate.opsForSet().size(CacheConstants.SECKILL_ORDERED_SET_KEY + SKU_ID));
    }

    @Test
    @DisplayName("一人一单：同用户二次抢购被 Lua 判重拒绝，库存不再扣减")
    void execute_repeatedBlockedByLua() {
        String path = seckillService.createSeckillPath(SKU_ID, new com.mall.dto.SeckillPathDTO());

        seckillService.execute(SKU_ID, path, buildDto());
        assertEquals("9", stringRedisTemplate.opsForValue().get(CacheConstants.SECKILL_STOCK_KEY + SKU_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, path, buildDto()));
        assertEquals("您已抢购过该商品，请勿重复抢购", ex.getMessage());

        assertEquals("9", stringRedisTemplate.opsForValue().get(CacheConstants.SECKILL_STOCK_KEY + SKU_ID));
    }

    @Test
    @DisplayName("库存耗尽：10 个不同用户各抢一次后，第 11 次返回售罄")
    void execute_soldOutAfterStockExhausted() {
        for (int i = 0; i < 10; i++) {
            TestSecurityHelper.loginAs(2000L + i);
            String path = seckillService.createSeckillPath(SKU_ID, new com.mall.dto.SeckillPathDTO());
            seckillService.execute(SKU_ID, path, buildDto());
        }
        TestSecurityHelper.loginAs(USER_ID);
        assertEquals("0", stringRedisTemplate.opsForValue().get(CacheConstants.SECKILL_STOCK_KEY + SKU_ID));

        String path = seckillService.createSeckillPath(SKU_ID, new com.mall.dto.SeckillPathDTO());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.execute(SKU_ID, path, buildDto()));
        assertEquals("已抢光，下次早点来~", ex.getMessage());
    }

    @Test
    @DisplayName("结果轮询：未落单时 result key 不存在 -> QUEUING")
    void result_queuingWhenNoResult() {
        SeckillResultVO vo = seckillService.result(SKU_ID);
        assertEquals(SeckillResultVO.STATUS_QUEUING, vo.getStatus());
    }

    private com.mall.dto.SeckillExecuteDTO buildDto() {
        com.mall.dto.SeckillExecuteDTO dto = new com.mall.dto.SeckillExecuteDTO();
        dto.setReceiverName("集成测试");
        dto.setReceiverPhone("13900000000");
        dto.setReceiverAddress("测试地址");
        return dto;
    }

    @TestConfiguration
    @Import(RedisConfig.class)
    static class TestConfig {

        @Bean
        public RedisConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", redisPort);
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        @Bean
        public SeckillProperties seckillProperties() {
            SeckillProperties p = new SeckillProperties();
            p.setCaptchaEnabled(false);
            p.setLocalMarkEnabled(true);
            p.setPathSalt("integration-test-salt");
            p.setPathTtlSeconds(60);
            p.setResultTtlSeconds(60);
            return p;
        }

        @Bean
        public SeckillMetrics seckillMetrics() {
            return new SeckillMetrics(new SimpleMeterRegistry());
        }

        @Bean
        public SeckillServiceImpl seckillService(SeckillProperties properties,
                                                 SeckillMetrics metrics,
                                                 StringRedisTemplate stringRedisTemplate,
                                                 RedisTemplate<String, Object> redisTemplate,
                                                 RedisConnectionFactory connectionFactory) {
            return new SeckillServiceImpl(
                    null, null, null, null, mock(com.mall.mapper.MqConsumeLogMapper.class),
                    null, null, null, null,
                    redisTemplate, stringRedisTemplate, mock(org.redisson.api.RedissonClient.class),
                    properties, new com.mall.config.OrderProperties(),
                    mock(SeckillOrderSender.class), mock(OrderDelaySender.class),
                    mock(CaptchaService.class), metrics,
                    com.github.benmanes.caffeine.cache.Caffeine.newBuilder().build());
        }
    }
}
