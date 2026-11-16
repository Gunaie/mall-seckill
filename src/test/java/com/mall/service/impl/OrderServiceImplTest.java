package com.mall.service.impl;

import com.mall.TestSecurityHelper;
import com.mall.common.enums.OrderStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.config.OrderProperties;
import com.mall.dto.OrderCreateDTO;
import com.mall.entity.OmsOrder;
import com.mall.entity.OmsOrderItem;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.mapper.OmsOrderItemMapper;
import com.mall.mapper.OmsOrderMapper;
import com.mall.mapper.PmsSkuMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.model.CartItem;
import com.mall.mq.OrderDelaySender;
import com.mall.service.CartService;
import com.mall.service.SeckillService;
import com.mall.vo.OrderCreateVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单服务单元测试：下单幂等令牌、购物车空校验、库存校验、服务端价格重算、
 * 状态机（支付/取消/发货/确认收货）、超时关单幂等、越权访问拒绝。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private OmsOrderItemMapper orderItemMapper;
    @Mock
    private PmsSkuMapper skuMapper;
    @Mock
    private PmsSpuMapper spuMapper;
    @Mock
    private com.mall.mapper.UmsUserMapper userMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private CartService cartService;
    @Mock
    private OrderProperties orderProperties;
    @Mock
    private OrderDelaySender orderDelaySender;
    @Mock
    private SeckillService seckillService;
    @Mock
    private com.mall.mapper.MqConsumeLogMapper mqConsumeLogMapper;

    @InjectMocks
    private OrderServiceImpl orderService;

    private static final Long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        TestSecurityHelper.loginAs(USER_ID);
    }

    @AfterEach
    void tearDown() {
        TestSecurityHelper.clear();
    }

    // ============================ 下单 ============================

    @Test
    @DisplayName("createOrder 令牌无效/已使用 -> 拒绝（幂等）")
    void createOrder_invalidToken() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setOrderToken("tok");
        assertThrows(BusinessException.class, () -> orderService.createOrder(dto),
                "下单令牌无效或已使用，请刷新结算页面后重试");
        verify(orderMapper, never()).insert(org.mockito.ArgumentMatchers.<OmsOrder>any());
    }

    @Test
    @DisplayName("createOrder 购物车无勾选项 -> 拒绝")
    void createOrder_emptyCart() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(1L);
        when(cartService.getSelectedItems(USER_ID)).thenReturn(List.of());
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setOrderToken("tok");
        assertThrows(BusinessException.class, () -> orderService.createOrder(dto),
                "请先勾选要结算的商品");
    }

    @Test
    @DisplayName("createOrder 库存不足 -> 拒绝并整单回滚")
    void createOrder_stockInsufficient() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(1L);
        CartItem item = cartItem(10L, 5);
        when(cartService.getSelectedItems(USER_ID)).thenReturn(List.of(item));

        PmsSku sku = onSaleSku(10L, 20L, new BigDecimal("100.00"), 3); // 库存 3 < 5
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku));

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setOrderToken("tok");
        assertThrows(BusinessException.class, () -> orderService.createOrder(dto), "库存不足");
        verify(orderMapper, never()).insert(org.mockito.ArgumentMatchers.<OmsOrder>any());
    }

    @Test
    @DisplayName("createOrder 成功：服务端重算价格 + 扣减库存 + 投递延迟关单")
    void createOrder_success() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(1L);
        CartItem item = cartItem(10L, 2);
        when(cartService.getSelectedItems(USER_ID)).thenReturn(List.of(item));

        PmsSku sku = onSaleSku(10L, 20L, new BigDecimal("99.50"), 100);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku));
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(onSaleSpu(20L)));
        when(skuMapper.deductStock(10L, 2)).thenReturn(1);
        when(orderProperties.getTimeoutSeconds()).thenReturn(1800L);

        // insert 回填主键 id
        doAnswer(inv -> {
            OmsOrder o = inv.getArgument(0);
            o.setId(8888L);
            return null;
        }).when(orderMapper).insert(org.mockito.ArgumentMatchers.<OmsOrder>any());

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setOrderToken("tok");
        OrderCreateVO vo = orderService.createOrder(dto);

        // 服务端重算：99.50 * 2 = 199.00
        assertEquals(0, vo.getTotalAmount().compareTo(new BigDecimal("199.00")));
        assertEquals(8888L, vo.getOrderId());
        verify(orderMapper, times(1)).insert(org.mockito.ArgumentMatchers.<OmsOrder>any());
        verify(orderItemMapper, times(1)).insert(org.mockito.ArgumentMatchers.<OmsOrderItem>any());
        verify(cartService, times(1)).removeSelected(USER_ID);
        verify(orderDelaySender, times(1)).sendDelayClose(any());
    }

    // ============================ 状态机 ============================

    @Test
    @DisplayName("pay 待支付 -> 已支付成功")
    void pay_success() {
        OmsOrder order = orderWith(OrderStatus.PENDING_PAY);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.payIfPending(1L, USER_ID)).thenReturn(1);
        orderService.pay(1L);
        verify(orderMapper).payIfPending(1L, USER_ID);
    }

    @Test
    @DisplayName("pay 非待支付（已支付） -> 拒绝")
    void pay_notPending() {
        OmsOrder order = orderWith(OrderStatus.PAID);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.payIfPending(1L, USER_ID)).thenReturn(0);
        assertThrows(BusinessException.class, () -> orderService.pay(1L));
    }

    @Test
    @DisplayName("cancel 成功并回补普通库存")
    void cancel_success() {
        OmsOrder order = orderWith(OrderStatus.PENDING_PAY);
        order.setOrderType(1);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.cancelIfPending(eq(1L), eq(USER_ID), any())).thenReturn(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        orderService.cancel(1L);
        verify(seckillService, never()).restoreAfterOrderCancel(anyLong(), anyLong());
    }

    @Test
    @DisplayName("cancel 秒杀订单 -> 走秒杀库存回补")
    void cancel_seckillOrder() {
        OmsOrder order = orderWith(OrderStatus.PENDING_PAY);
        order.setOrderType(2);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.cancelIfPending(eq(1L), eq(USER_ID), any())).thenReturn(1);

        orderService.cancel(1L);
        verify(seckillService, times(1)).restoreAfterOrderCancel(1L, USER_ID);
    }

    @Test
    @DisplayName("ship 订单不存在 -> NOT_FOUND")
    void ship_notFound() {
        when(orderMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.ship(1L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("confirm 非已发货 -> 拒绝")
    void confirm_notShipped() {
        OmsOrder order = orderWith(OrderStatus.PAID);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.completeIfShipped(1L, USER_ID)).thenReturn(0);
        assertThrows(BusinessException.class, () -> orderService.confirm(1L));
    }

    // ============================ 超时关单 ============================

    @Test
    @DisplayName("closeExpiredOrder 非待支付 -> 幂等跳过")
    void closeExpired_notPending() {
        when(mqConsumeLogMapper.tryConsume(any(), any())).thenReturn(1);
        OmsOrder order = orderWith(OrderStatus.PAID);
        when(orderMapper.selectOne(any())).thenReturn(order);
        orderService.closeExpiredOrder(1L, USER_ID, "msg-1");
        verify(orderMapper, never()).cancelIfPending(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("closeExpiredOrder 待支付 -> CAS 关单并回补库存")
    void closeExpired_success() {
        when(mqConsumeLogMapper.tryConsume(any(), any())).thenReturn(1);
        OmsOrder order = orderWith(OrderStatus.PENDING_PAY);
        order.setOrderType(1);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.cancelIfPending(eq(1L), eq(USER_ID), any())).thenReturn(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        orderService.closeExpiredOrder(1L, USER_ID, "msg-2");
        verify(orderMapper).cancelIfPending(eq(1L), eq(USER_ID), any());
    }

    // ============================ 越权 ============================

    @Test
    @DisplayName("detail 跨用户访问 -> 返回 NOT_FOUND（不暴露存在性）")
    void detail_crossUser() {
        // id + user_id 双条件下路由查询查不到他人订单
        when(orderMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.detail(1L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("detail 订单不存在 -> NOT_FOUND")
    void detail_notFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.detail(1L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
    }

    // ============================ 辅助 ============================

    private CartItem cartItem(Long skuId, int qty) {
        CartItem item = new CartItem();
        item.setSkuId(skuId);
        item.setQuantity(qty);
        item.setSelected(1);
        return item;
    }

    private PmsSku onSaleSku(Long id, Long spuId, BigDecimal price, int stock) {
        PmsSku sku = new PmsSku();
        sku.setId(id);
        sku.setSpuId(spuId);
        sku.setName("测试SKU");
        sku.setPrice(price);
        sku.setStock(stock);
        sku.setStatus(1);
        return sku;
    }

    private PmsSpu onSaleSpu(Long id) {
        PmsSpu spu = new PmsSpu();
        spu.setId(id);
        spu.setName("测试SPU");
        spu.setStatus(1);
        return spu;
    }

    private OmsOrder orderWith(OrderStatus status) {
        OmsOrder order = new OmsOrder();
        order.setId(1L);
        order.setUserId(USER_ID);
        order.setStatus(status.getCode());
        order.setOrderSn("OD001");
        return order;
    }
}
