package com.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.common.util.SpecTextUtil;
import com.mall.dto.CartAddDTO;
import com.mall.dto.CartQuantityDTO;
import com.mall.dto.CartSelectDTO;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.mapper.PmsSkuMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.model.CartItem;
import com.mall.security.SecurityUtils;
import com.mall.service.CartService;
import com.mall.vo.CartItemVO;
import com.mall.vo.CartVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车服务实现：
 * Redis Hash 结构 cart:user:{userId}，field=skuId，value=购物车项 JSON；
 * 只持久化用户意图，价格/库存/名称实时查库拼装。
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final PmsSkuMapper skuMapper;
    private final PmsSpuMapper spuMapper;

    @Override
    public void addToCart(CartAddDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        PmsSku sku = loadOnSaleSku(dto.getSkuId());

        String key = cartKey(userId);
        String field = String.valueOf(dto.getSkuId());
        CartItem item = (CartItem) redisTemplate.opsForHash().get(key, field);
        int oldQuantity = item == null ? 0 : item.getQuantity();
        int newQuantity = oldQuantity + dto.getQuantity();
        if (newQuantity > sku.getStock()) {
            throw new BusinessException("库存不足，该商品最多可购买 " + sku.getStock() + " 件");
        }
        if (item == null) {
            item = new CartItem();
            item.setSkuId(dto.getSkuId());
            item.setSelected(1);
            item.setAddTime(System.currentTimeMillis());
        }
        item.setQuantity(newQuantity);
        redisTemplate.opsForHash().put(key, field, item);
    }

    @Override
    public void updateQuantity(CartQuantityDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        String key = cartKey(userId);
        String field = String.valueOf(dto.getSkuId());
        CartItem item = (CartItem) redisTemplate.opsForHash().get(key, field);
        if (item == null) {
            throw new BusinessException("购物车中没有该商品");
        }
        PmsSku sku = loadOnSaleSku(dto.getSkuId());
        if (dto.getQuantity() > sku.getStock()) {
            throw new BusinessException("库存不足，该商品最多可购买 " + sku.getStock() + " 件");
        }
        item.setQuantity(dto.getQuantity());
        redisTemplate.opsForHash().put(key, field, item);
    }

    @Override
    public void selectItem(CartSelectDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        String key = cartKey(userId);
        String field = String.valueOf(dto.getSkuId());
        CartItem item = (CartItem) redisTemplate.opsForHash().get(key, field);
        if (item == null) {
            throw new BusinessException("购物车中没有该商品");
        }
        item.setSelected(Boolean.TRUE.equals(dto.getSelected()) ? 1 : 0);
        redisTemplate.opsForHash().put(key, field, item);
    }

    @Override
    public void selectAll(Boolean selected) {
        Long userId = SecurityUtils.getCurrentUserId();
        String key = cartKey(userId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return;
        }
        int flag = Boolean.TRUE.equals(selected) ? 1 : 0;
        entries.values().forEach(v -> ((CartItem) v).setSelected(flag));
        redisTemplate.opsForHash().putAll(key, entries);
    }

    @Override
    public void removeItem(Long skuId) {
        Long userId = SecurityUtils.getCurrentUserId();
        redisTemplate.opsForHash().delete(cartKey(userId), String.valueOf(skuId));
    }

    @Override
    public CartVO listCart() {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(cartKey(userId));

        CartVO cart = new CartVO();
        cart.setItems(new ArrayList<>());
        cart.setTotalCount(0);
        cart.setSelectedCount(0);
        cart.setSelectedAmount(BigDecimal.ZERO);
        if (entries.isEmpty()) {
            return cart;
        }

        List<CartItem> stored = entries.values().stream()
                .map(v -> (CartItem) v)
                .sorted(Comparator.comparing(CartItem::getAddTime))
                .toList();

        // 批量查询 SKU 与 SPU，避免 N+1
        Set<Long> skuIds = stored.stream().map(CartItem::getSkuId).collect(Collectors.toSet());
        Map<Long, PmsSku> skuMap = skuMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(PmsSku::getId, Function.identity()));
        Set<Long> spuIds = skuMap.values().stream().map(PmsSku::getSpuId).collect(Collectors.toSet());
        Map<Long, PmsSpu> spuMap = spuIds.isEmpty() ? Map.of()
                : spuMapper.selectList(Wrappers.<PmsSpu>lambdaQuery().in(PmsSpu::getId, spuIds)).stream()
                .collect(Collectors.toMap(PmsSpu::getId, Function.identity()));

        int totalCount = 0;
        int selectedCount = 0;
        BigDecimal selectedAmount = BigDecimal.ZERO;

        for (CartItem item : stored) {
            CartItemVO vo = new CartItemVO();
            vo.setSkuId(item.getSkuId());
            vo.setQuantity(item.getQuantity());
            vo.setSelected(Objects.equals(item.getSelected(), 1));

            PmsSku sku = skuMap.get(item.getSkuId());
            PmsSpu spu = sku == null ? null : spuMap.get(sku.getSpuId());
            boolean onSale = sku != null && Objects.equals(sku.getStatus(), 1)
                    && spu != null && Objects.equals(spu.getStatus(), 1);
            vo.setOnSale(onSale);
            if (sku != null) {
                vo.setSpuId(sku.getSpuId());
                vo.setSkuCode(sku.getSkuCode());
                vo.setSkuName(sku.getName());
                vo.setSpecText(SpecTextUtil.toText(sku.getSpecJson()));
                vo.setImage(sku.getImage());
                vo.setPrice(sku.getPrice());
                vo.setStock(sku.getStock());
                vo.setOnSale(onSale);
                vo.setStockEnough(onSale && sku.getStock() >= item.getQuantity());
            } else {
                vo.setOnSale(false);
                vo.setStockEnough(false);
            }

            totalCount += item.getQuantity();
            if (vo.getSelected() && Boolean.TRUE.equals(vo.getOnSale())
                    && Boolean.TRUE.equals(vo.getStockEnough())) {
                selectedCount += item.getQuantity();
                selectedAmount = selectedAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
            cart.getItems().add(vo);
        }

        cart.setTotalCount(totalCount);
        cart.setSelectedCount(selectedCount);
        cart.setSelectedAmount(selectedAmount);
        return cart;
    }

    @Override
    public List<CartItem> getSelectedItems(Long userId) {
        return redisTemplate.opsForHash().<Object, Object>entries(cartKey(userId)).values().stream()
                .map(v -> (CartItem) v)
                .filter(item -> Objects.equals(item.getSelected(), 1))
                .toList();
    }

    @Override
    public void removeSelected(Long userId) {
        String key = cartKey(userId);
        List<String> selectedFields = redisTemplate.opsForHash().<Object, Object>entries(key).values().stream()
                .map(v -> (CartItem) v)
                .filter(item -> Objects.equals(item.getSelected(), 1))
                .map(item -> String.valueOf(item.getSkuId()))
                .toList();
        if (!selectedFields.isEmpty()) {
            redisTemplate.opsForHash().delete(key, selectedFields.toArray());
        }
    }

    // ============================== 私有方法 ==============================

    private String cartKey(Long userId) {
        return CacheConstants.CART_KEY_PREFIX + userId;
    }

    /**
     * 加载可售 SKU：存在、上架且所属 SPU 上架
     */
    private PmsSku loadOnSaleSku(Long skuId) {
        PmsSku sku = skuMapper.selectById(skuId);
        if (sku == null || !Objects.equals(sku.getStatus(), 1)) {
            throw new BusinessException("商品不存在或已下架");
        }
        PmsSpu spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null || !Objects.equals(spu.getStatus(), 1)) {
            throw new BusinessException("商品不存在或已下架");
        }
        return sku;
    }
}
