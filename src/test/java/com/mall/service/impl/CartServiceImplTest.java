package com.mall.service.impl;

import com.mall.TestSecurityHelper;
import com.mall.common.exception.BusinessException;
import com.mall.dto.CartAddDTO;
import com.mall.dto.CartQuantityDTO;
import com.mall.dto.CartSelectDTO;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.mapper.PmsSkuMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.model.CartItem;
import com.mall.vo.CartVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单元测试：加购库存校验、数量修改、勾选、列表聚合（价格/库存实时查库）。
 */
@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    @SuppressWarnings("rawtypes")
    private HashOperations hashOps;
    @Mock
    private PmsSkuMapper skuMapper;
    @Mock
    private PmsSpuMapper spuMapper;

    @InjectMocks
    private CartServiceImpl cartService;

    private static final Long USER_ID = 1001L;
    private static final Long SKU_ID = 10L;

    @BeforeEach
    void setUp() {
        TestSecurityHelper.loginAs(USER_ID);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    @AfterEach
    void tearDown() {
        TestSecurityHelper.clear();
    }

    @Test
    @DisplayName("addToCart 新商品：写入购物车，selected=1")
    void addToCart_new() {
        PmsSku sku = sku(SKU_ID, 20L, new BigDecimal("99.00"), 100);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku);
        when(spuMapper.selectById(20L)).thenReturn(spu(20L));
        when(hashOps.get(anyString(), eq(String.valueOf(SKU_ID)))).thenReturn(null);

        CartAddDTO dto = new CartAddDTO();
        dto.setSkuId(SKU_ID);
        dto.setQuantity(2);
        cartService.addToCart(dto);

        verify(hashOps).put(anyString(), eq(String.valueOf(SKU_ID)), any(CartItem.class));
    }

    @Test
    @DisplayName("addToCart 累加后超库存 -> 拒绝")
    void addToCart_exceedStock() {
        PmsSku sku = sku(SKU_ID, 20L, new BigDecimal("99.00"), 3);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku);
        when(spuMapper.selectById(20L)).thenReturn(spu(20L));
        CartItem existing = new CartItem();
        existing.setSkuId(SKU_ID);
        existing.setQuantity(2);
        when(hashOps.get(anyString(), eq(String.valueOf(SKU_ID)))).thenReturn(existing);

        CartAddDTO dto = new CartAddDTO();
        dto.setSkuId(SKU_ID);
        dto.setQuantity(2); // 2+2=4 > 3
        assertThrows(BusinessException.class, () -> cartService.addToCart(dto), "库存不足");
    }

    @Test
    @DisplayName("updateQuantity 商品不在购物车 -> 拒绝")
    void updateQuantity_notInCart() {
        when(hashOps.get(anyString(), anyString())).thenReturn(null);
        CartQuantityDTO dto = new CartQuantityDTO();
        dto.setSkuId(SKU_ID);
        dto.setQuantity(1);
        assertThrows(BusinessException.class, () -> cartService.updateQuantity(dto));
    }

    @Test
    @DisplayName("listCart 空购物车 -> 返回空列表与零汇总")
    void listCart_empty() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());
        CartVO vo = cartService.listCart();
        assertEquals(0, vo.getItems().size());
        assertEquals(0, vo.getTotalCount());
        assertEquals(0, vo.getSelectedAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("listCart 聚合：勾选且库存充足才计入选中金额")
    void listCart_aggregate() {
        CartItem item = new CartItem();
        item.setSkuId(SKU_ID);
        item.setQuantity(2);
        item.setSelected(1);
        item.setAddTime(1L);
        Map<Object, Object> entries = new HashMap<>();
        entries.put(String.valueOf(SKU_ID), item);
        when(hashOps.entries(anyString())).thenReturn(entries);

        PmsSku sku = sku(SKU_ID, 20L, new BigDecimal("50.00"), 10);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku));
        when(spuMapper.selectList(any())).thenReturn(List.of(spu(20L)));

        CartVO vo = cartService.listCart();
        assertEquals(2, vo.getTotalCount());
        assertEquals(2, vo.getSelectedCount());
        // 50.00 * 2 = 100.00
        assertEquals(0, vo.getSelectedAmount().compareTo(new BigDecimal("100.00")));
    }

    @Test
    @DisplayName("getSelectedItems 只返回 selected=1 的项")
    void getSelectedItems_filter() {
        CartItem selected = new CartItem();
        selected.setSkuId(1L);
        selected.setSelected(1);
        CartItem unselected = new CartItem();
        unselected.setSkuId(2L);
        unselected.setSelected(0);
        Map<Object, Object> entries = new HashMap<>();
        entries.put("1", selected);
        entries.put("2", unselected);
        when(hashOps.entries(anyString())).thenReturn(entries);

        List<CartItem> result = cartService.getSelectedItems(USER_ID);
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getSkuId());
    }

    private PmsSku sku(Long id, Long spuId, BigDecimal price, int stock) {
        PmsSku s = new PmsSku();
        s.setId(id);
        s.setSpuId(spuId);
        s.setName("SKU");
        s.setPrice(price);
        s.setStock(stock);
        s.setStatus(1);
        return s;
    }

    private PmsSpu spu(Long id) {
        PmsSpu s = new PmsSpu();
        s.setId(id);
        s.setName("SPU");
        s.setStatus(1);
        return s;
    }
}
