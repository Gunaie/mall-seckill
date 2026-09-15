package com.mall.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.config.CacheProperties;
import com.mall.entity.PmsCategory;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.mapper.PmsCategoryMapper;
import com.mall.mapper.PmsSkuMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.vo.SpuDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品详情多级缓存单元测试：
 * 验证 Caffeine(L1) -> Redis(L2) -> DB 三级回源、空值防穿透、Cache Aside 失效策略。
 * Caffeine 使用真实实例以验证本地缓存命中/回填行为。
 */
@ExtendWith(MockitoExtension.class)
class PmsSpuServiceImplCacheTest {

    @Mock
    private PmsSpuMapper spuMapper;
    @Mock
    private PmsSkuMapper skuMapper;
    @Mock
    private PmsCategoryMapper categoryMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;

    private CacheProperties cacheProperties;
    private Cache<String, Object> localCache;
    private PmsSpuServiceImpl spuService;

    private static final Long SPU_ID = 100L;
    private static final String CACHE_KEY = CacheConstants.SPU_DETAIL_KEY + SPU_ID;

    @BeforeEach
    void setUp() {
        cacheProperties = new CacheProperties();
        localCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(
                java.time.Duration.ofSeconds(cacheProperties.getSpuDetailLocalTtl())).build();
        spuService = new PmsSpuServiceImpl(skuMapper, categoryMapper, redisTemplate,
                redissonClient, cacheProperties, new com.fasterxml.jackson.databind.ObjectMapper(), localCache);
        // ServiceImpl 的 baseMapper 是 protected 字段，通过反射注入 mock
        ReflectionTestUtils.setField(spuService, "baseMapper", spuMapper);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redissonClient.getLock(CacheConstants.SPU_DETAIL_LOCK_KEY + SPU_ID)).thenReturn(rLock);
    }

    private SpuDetailVO buildDetailVO() {
        SpuDetailVO vo = new SpuDetailVO();
        vo.setId(SPU_ID);
        vo.setName("测试商品");
        vo.setCategoryName("手机数码");
        return vo;
    }

    private PmsSpu buildSpuEntity() {
        PmsSpu spu = new PmsSpu();
        spu.setId(SPU_ID);
        spu.setName("测试商品");
        spu.setCategoryId(1L);
        spu.setStatus(1);
        spu.setImages("[\"img1.png\"]");
        return spu;
    }

    // ============================ L1 本地缓存 ============================

    @Test
    @DisplayName("getDetail: Caffeine 命中 SpuDetailVO -> 直接返回，不查 Redis/DB")
    void getDetail_caffeineHit_returnsDirectly() {
        SpuDetailVO cached = buildDetailVO();
        localCache.put(CACHE_KEY, cached);

        SpuDetailVO result = spuService.getDetail(SPU_ID);

        assertEquals(cached, result);
        verify(redisTemplate, never()).opsForValue();
        verify(spuMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("getDetail: Caffeine 命中空值占位符 -> 抛 NOT_FOUND，不查 Redis/DB")
    void getDetail_caffeineNullPlaceholder_throwsNotFound() {
        localCache.put(CACHE_KEY, CacheConstants.NULL_PLACEHOLDER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> spuService.getDetail(SPU_ID));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
        verify(redisTemplate, never()).opsForValue();
        verify(spuMapper, never()).selectById(anyLong());
    }

    // ============================ L2 Redis 缓存 ============================

    @Test
    @DisplayName("getDetail: Redis 命中 SpuDetailVO -> 回填 Caffeine，返回缓存对象")
    void getDetail_redisHit_backfillsCaffeine() {
        // Caffeine 未命中
        SpuDetailVO cached = buildDetailVO();
        when(valueOps.get(CACHE_KEY)).thenReturn(cached);

        SpuDetailVO result = spuService.getDetail(SPU_ID);

        assertEquals(cached, result);
        // Redis 命中后回填本地缓存
        assertEquals(cached, localCache.getIfPresent(CACHE_KEY));
        // 未查 DB
        verify(spuMapper, never()).selectById(anyLong());
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("getDetail: Redis 命中空值占位符 -> 回填 Caffeine 空值，抛 NOT_FOUND")
    void getDetail_redisNullPlaceholder_backfillsCaffeineAndThrows() {
        when(valueOps.get(CACHE_KEY)).thenReturn(CacheConstants.NULL_PLACEHOLDER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> spuService.getDetail(SPU_ID));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
        // 空值回填本地缓存
        assertEquals(CacheConstants.NULL_PLACEHOLDER, localCache.getIfPresent(CACHE_KEY));
        verify(spuMapper, never()).selectById(anyLong());
    }

    // ============================ DB 回源重建 ============================

    @Test
    @DisplayName("getDetail: 缓存未命中 + DB 有数据 -> 加锁回源，写 Redis + Caffeine，返回详情")
    void getDetail_cacheMiss_dbHit_rebuildsCache() throws InterruptedException {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(rLock.tryLock(5, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        PmsSpu spu = buildSpuEntity();
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
        PmsCategory category = new PmsCategory();
        category.setId(1L);
        category.setName("手机数码");
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(skuMapper.selectList(any())).thenReturn(Collections.emptyList());

        SpuDetailVO result = spuService.getDetail(SPU_ID);

        assertNotNull(result);
        assertEquals(SPU_ID, result.getId());
        assertEquals("测试商品", result.getName());
        // 回源后写入 Redis（带 TTL）和 Caffeine
        verify(valueOps).set(eq(CACHE_KEY), any(SpuDetailVO.class), anyLong(), eq(TimeUnit.SECONDS));
        assertNotNull(localCache.getIfPresent(CACHE_KEY));
        // 释放锁
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("getDetail: 缓存未命中 + DB 无数据 -> 写空值占位符到 Redis + Caffeine，抛 NOT_FOUND")
    void getDetail_cacheMiss_dbNull_writesNullPlaceholder() throws InterruptedException {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(rLock.tryLock(5, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(spuMapper.selectById(SPU_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> spuService.getDetail(SPU_ID));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());

        // 空值写入 Redis（TTL = nullValueTtl）和 Caffeine
        verify(valueOps).set(eq(CACHE_KEY), eq(CacheConstants.NULL_PLACEHOLDER),
                eq(cacheProperties.getNullValueTtl()), eq(TimeUnit.SECONDS));
        assertEquals(CacheConstants.NULL_PLACEHOLDER, localCache.getIfPresent(CACHE_KEY));
    }

    @Test
    @DisplayName("getDetail: 拿到锁后 DCL 二次检查 Redis 已被其他线程重建 -> 直接返回缓存")
    void getDetail_doubleCheckAfterLock_redisRebuiltByOther() throws InterruptedException {
        // 第一次查 Redis 未命中
        when(valueOps.get(CACHE_KEY)).thenReturn(null).thenReturn(buildDetailVO());
        when(rLock.tryLock(5, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        SpuDetailVO result = spuService.getDetail(SPU_ID);

        assertNotNull(result);
        // DCL 命中，未查 DB
        verify(spuMapper, never()).selectById(anyLong());
    }

    // ============================ 缓存失效（Cache Aside） ============================

    @Test
    @DisplayName("updateSpu: 更新后先删 Redis 再删 Caffeine")
    void updateSpu_evictsCache() {
        // 预置：Caffeine 有旧缓存
        localCache.put(CACHE_KEY, buildDetailVO());

        PmsSpu exist = buildSpuEntity();
        when(spuMapper.selectById(SPU_ID)).thenReturn(exist);
        PmsCategory category = new PmsCategory();
        category.setId(1L);
        category.setLevel(3);
        when(categoryMapper.selectById(1L)).thenReturn(category);

        com.mall.dto.SpuSaveDTO dto = new com.mall.dto.SpuSaveDTO();
        dto.setId(SPU_ID);
        dto.setName("更新后商品");
        dto.setCategoryId(1L);
        dto.setStatus(1);
        dto.setImages(List.of("new.png"));
        dto.setSkus(Collections.emptyList());

        spuService.updateSpu(dto);

        // Cache Aside：先删 Redis 再删本地缓存
        verify(redisTemplate).delete(CACHE_KEY);
        // Caffeine 已失效
        assertTrue(localCache.getIfPresent(CACHE_KEY) == null);
    }

    @Test
    @DisplayName("deleteSpu: 删除后失效缓存")
    void deleteSpu_evictsCache() {
        localCache.put(CACHE_KEY, buildDetailVO());

        PmsSpu exist = buildSpuEntity();
        when(spuMapper.selectById(SPU_ID)).thenReturn(exist);

        spuService.deleteSpu(SPU_ID);

        verify(redisTemplate).delete(CACHE_KEY);
        assertTrue(localCache.getIfPresent(CACHE_KEY) == null);
    }
}
