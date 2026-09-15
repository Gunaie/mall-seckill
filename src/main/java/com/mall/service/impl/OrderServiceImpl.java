package com.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.constant.CacheConstants;
import com.mall.common.constant.MqConstants;
import com.mall.common.enums.OrderStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.page.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.common.util.SpecTextUtil;
import com.mall.config.OrderProperties;
import com.mall.dto.OrderCreateDTO;
import com.mall.dto.OrderPageQuery;
import com.mall.entity.OmsOrder;
import com.mall.entity.OmsOrderItem;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.entity.UmsUser;
import com.mall.mapper.OmsOrderItemMapper;
import com.mall.mapper.OmsOrderMapper;
import com.mall.mapper.PmsSkuMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.mapper.UmsUserMapper;
import com.mall.mapper.MqConsumeLogMapper;
import com.mall.model.CartItem;
import com.mall.mq.OrderDelaySender;
import com.mall.mq.message.OrderDelayMessage;
import com.mall.security.SecurityUtils;
import com.mall.service.CartService;
import com.mall.service.OrderService;
import com.mall.service.SeckillService;
import com.mall.vo.OrderCreateVO;
import com.mall.vo.OrderDetailVO;
import com.mall.vo.OrderItemVO;
import com.mall.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final PmsSkuMapper skuMapper;
    private final PmsSpuMapper spuMapper;
    private final UmsUserMapper userMapper;
    private final MqConsumeLogMapper mqConsumeLogMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CartService cartService;
    private final OrderProperties orderProperties;
    private final OrderDelaySender orderDelaySender;
    private final SeckillService seckillService;

    /**
     * 幂等令牌一次性消费 Lua：存在即删除（原子操作），返回 1 消费成功，0 无效/重复
     */
    private static final DefaultRedisScript<Long> CONSUME_TOKEN_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if redis.call('get', KEYS[1]) then " +
                "    return redis.call('del', KEYS[1]) " +
                "else " +
                "    return 0 " +
                "end");
        script.setResultType(Long.class);
        CONSUME_TOKEN_SCRIPT = script;
    }

    // ============================== 下单 ==============================

    @Override
    public String issueToken() {
        Long userId = SecurityUtils.getCurrentUserId();
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(tokenKey(userId, token), "1",
                orderProperties.getTokenTtlSeconds(), TimeUnit.SECONDS);
        return token;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateVO createOrder(OrderCreateDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 1. 幂等令牌一次性消费：防止用户重复点击/网络重试导致重复下单
        Long consumed = redisTemplate.execute(CONSUME_TOKEN_SCRIPT,
                Collections.singletonList(tokenKey(userId, dto.getOrderToken())));
        if (consumed == null || consumed == 0L) {
            throw new BusinessException("下单令牌无效或已使用，请刷新结算页面后重试");
        }

        // 2. 商品只取自服务端购物车勾选项，前端传入的商品/价格一律不相信
        List<CartItem> selectedItems = cartService.getSelectedItems(userId);
        if (selectedItems.isEmpty()) {
            throw new BusinessException("请先勾选要结算的商品");
        }

        // 3. 批量加载 SKU/SPU，校验在售状态与库存
        Set<Long> skuIds = selectedItems.stream().map(CartItem::getSkuId).collect(Collectors.toSet());
        Map<Long, PmsSku> skuMap = skuMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(PmsSku::getId, Function.identity()));
        Set<Long> spuIds = skuMap.values().stream().map(PmsSku::getSpuId).collect(Collectors.toSet());
        Map<Long, PmsSpu> spuMap = spuIds.isEmpty() ? Map.of()
                : spuMapper.selectBatchIds(spuIds).stream()
                .collect(Collectors.toMap(PmsSpu::getId, Function.identity()));

        List<OrderItemContext> contexts = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem item : selectedItems) {
            PmsSku sku = skuMap.get(item.getSkuId());
            PmsSpu spu = sku == null ? null : spuMap.get(sku.getSpuId());
            if (sku == null || !Objects.equals(sku.getStatus(), 1)
                    || spu == null || !Objects.equals(spu.getStatus(), 1)) {
                throw new BusinessException("商品已下架，请刷新购物车后重试");
            }
            if (item.getQuantity() > sku.getStock()) {
                throw new BusinessException("商品【" + sku.getName() + "】库存不足");
            }
            BigDecimal lineTotal = sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            totalAmount = totalAmount.add(lineTotal);
            contexts.add(new OrderItemContext(sku, spu, item.getQuantity(), lineTotal));
        }

        // 4. SQL 条件扣减库存（stock >= quantity），影响行数 0 即并发下已被抢光，整单回滚
        for (OrderItemContext ctx : contexts) {
            int rows = skuMapper.deductStock(ctx.sku().getId(), ctx.quantity());
            if (rows == 0) {
                throw new BusinessException("商品【" + ctx.sku().getName() + "】库存不足，请刷新后重试");
            }
        }

        // 5. 落订单主表（雪花算法订单号，DB 唯一索引兜底）
        OmsOrder order = new OmsOrder();
        order.setOrderSn("OD" + IdWorker.getIdStr());
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setOrderType(1);
        order.setReceiverName(dto.getReceiverName());
        order.setReceiverPhone(dto.getReceiverPhone());
        order.setReceiverAddress(dto.getReceiverAddress());
        order.setRemark(dto.getRemark());
        order.setExpireTime(LocalDateTime.now().plusSeconds(orderProperties.getTimeoutSeconds()));
        orderMapper.insert(order);

        // 6. 落明细（名称/规格/单价全部为快照）
        for (OrderItemContext ctx : contexts) {
            PmsSku sku = ctx.sku();
            OmsOrderItem orderItem = new OmsOrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setOrderSn(order.getOrderSn());
            orderItem.setUserId(userId);
            orderItem.setSkuId(sku.getId());
            orderItem.setSpuId(sku.getSpuId());
            orderItem.setSpuName(ctx.spu().getName());
            orderItem.setSkuName(sku.getName());
            orderItem.setSpecText(SpecTextUtil.toText(sku.getSpecJson()));
            orderItem.setSkuImage(sku.getImage());
            orderItem.setPrice(sku.getPrice());
            orderItem.setQuantity(ctx.quantity());
            orderItem.setTotalPrice(ctx.lineTotal());
            orderItemMapper.insert(orderItem);
        }

        // 7. 清空购物车已勾选项（Redis 操作在事务内，失败则整单回滚，库存一并归还）
        cartService.removeSelected(userId);

        // 8. 本地事务提交成功后再投递延迟消息，避免「事务回滚但消息已发出」的误关单
        registerDelayCloseAfterCommit(order.getId(), order.getOrderSn());

        OrderCreateVO vo = new OrderCreateVO();
        vo.setOrderId(order.getId());
        vo.setOrderSn(order.getOrderSn());
        vo.setTotalAmount(totalAmount);
        vo.setExpireTime(order.getExpireTime());
        log.info("订单创建成功, orderId={}, orderSn={}, totalAmount={}", order.getId(), order.getOrderSn(), totalAmount);
        return vo;
    }

    // ============================== 状态流转 ==============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long id) {
        OmsOrder order = loadOwnedOrder(id, SecurityUtils.getCurrentUserId());
        int rows = orderMapper.payIfPending(id);
        if (rows == 0) {
            throw new BusinessException("订单状态为「" + OrderStatus.descOf(order.getStatus()) + "」，不能支付");
        }
        log.info("订单支付成功, orderId={}, orderSn={}", id, order.getOrderSn());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        OmsOrder order = loadOwnedOrder(id, userId);
        int rows = orderMapper.cancelIfPending(id, "用户主动取消");
        if (rows == 0) {
            throw new BusinessException("订单状态为「" + OrderStatus.descOf(order.getStatus()) + "」，不能取消");
        }
        restoreStockForOrder(order);
        log.info("用户取消订单, orderId={}, userId={}", id, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(Long id) {
        OmsOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        int rows = orderMapper.shipIfPaid(id);
        if (rows == 0) {
            throw new BusinessException("订单状态为「" + OrderStatus.descOf(order.getStatus()) + "」，不能发货");
        }
        log.info("订单发货, orderId={}, orderSn={}", id, order.getOrderSn());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        OmsOrder order = loadOwnedOrder(id, SecurityUtils.getCurrentUserId());
        int rows = orderMapper.completeIfShipped(id);
        if (rows == 0) {
            throw new BusinessException("订单状态为「" + OrderStatus.descOf(order.getStatus()) + "」，不能确认收货");
        }
        log.info("确认收货, orderId={}, orderSn={}", id, order.getOrderSn());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeExpiredOrder(Long orderId, String messageId) {
        // MQ 消费幂等：同一条关单消息只处理一次，防止重复关单/重复回补库存
        if (mqConsumeLogMapper.tryConsume(messageId, MqConstants.ORDER_CLOSE_QUEUE) == 0) {
            log.info("关单消息已消费，幂等跳过, messageId={}, orderId={}", messageId, orderId);
            return;
        }
        OmsOrder order = orderMapper.selectById(orderId);
        // 幂等关单：不存在直接忽略；非待支付（已支付/已取消）直接跳过
        if (order == null || !Objects.equals(order.getStatus(), OrderStatus.PENDING_PAY.getCode())) {
            log.info("延迟关单跳过（订单不存在或非待支付）, orderId={}", orderId);
            return;
        }
        int rows = orderMapper.cancelIfPending(orderId, "超时未支付，系统自动取消");
        if (rows == 0) {
            // 与支付/用户取消并发，CAS 落败者直接结束
            log.info("延迟关单 CAS 未命中（订单刚被支付或取消）, orderId={}", orderId);
            return;
        }
        restoreStockForOrder(order);
        log.info("订单超时自动关单并回补库存, orderId={}, orderSn={}", orderId, order.getOrderSn());
    }

    // ============================== 查询 ==============================

    @Override
    public OrderDetailVO detail(Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        OmsOrder order = loadOwnedOrder(id, userId);
        return toDetailVO(order, null);
    }

    @Override
    public OrderDetailVO adminDetail(Long id) {
        OmsOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return toDetailVO(order, usernameMap(Set.of(order.getUserId())));
    }

    @Override
    public PageResult<OrderVO> myPage(OrderPageQuery query) {
        return doPage(query, SecurityUtils.getCurrentUserId());
    }

    @Override
    public PageResult<OrderVO> adminPage(OrderPageQuery query) {
        return doPage(query, null);
    }

    // ============================== 私有方法 ==============================

    private PageResult<OrderVO> doPage(OrderPageQuery query, Long userId) {
        Page<OmsOrder> page = new Page<>(query.getPageNum(), query.getPageSize());
        orderMapper.selectPage(page, Wrappers.<OmsOrder>lambdaQuery()
                .eq(userId != null, OmsOrder::getUserId, userId)
                .eq(query.getStatus() != null, OmsOrder::getStatus, query.getStatus())
                .like(StringUtils.hasText(query.getOrderSn()), OmsOrder::getOrderSn, query.getOrderSn())
                .orderByDesc(OmsOrder::getId));

        List<OmsOrder> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(page, Collections.emptyList());
        }

        // 批量查明细并按订单分组，避免 N+1
        Set<Long> orderIds = records.stream().map(OmsOrder::getId).collect(Collectors.toSet());
        Map<Long, List<OrderItemVO>> itemMap = orderItemMapper.selectList(
                        Wrappers.<OmsOrderItem>lambdaQuery().in(OmsOrderItem::getOrderId, orderIds)).stream()
                .collect(Collectors.groupingBy(OmsOrderItem::getOrderId,
                        Collectors.mapping(this::toItemVO, Collectors.toList())));

        // 管理端批量查用户名
        Set<Long> userIds = records.stream().map(OmsOrder::getUserId).collect(Collectors.toSet());
        Map<Long, String> usernameMap = usernameMap(userIds);

        List<OrderVO> voList = records.stream().map(order -> {
            OrderVO vo = new OrderVO();
            BeanUtils.copyProperties(order, vo);
            vo.setStatusDesc(OrderStatus.descOf(order.getStatus()));
            vo.setUsername(usernameMap.get(order.getUserId()));
            vo.setItems(itemMap.getOrDefault(order.getId(), Collections.emptyList()));
            return vo;
        }).toList();
        return PageResult.of(page, voList);
    }

    private Map<Long, String> usernameMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UmsUser::getId, UmsUser::getUsername));
    }

    private OmsOrder loadOwnedOrder(Long id, Long userId) {
        OmsOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!Objects.equals(order.getUserId(), userId)) {
            // 不向其他用户暴露订单是否存在
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private void restoreStockByOrder(Long orderId) {
        List<OmsOrderItem> items = orderItemMapper.selectList(
                Wrappers.<OmsOrderItem>lambdaQuery().eq(OmsOrderItem::getOrderId, orderId));
        for (OmsOrderItem item : items) {
            skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
        }
    }

    /**
     * 订单取消/关单后的库存回补：秒杀订单回补秒杀库存（DB + Redis 计数 + 一人一单标记），
     * 普通订单回补 pms_sku 普通库存
     */
    private void restoreStockForOrder(OmsOrder order) {
        if (Objects.equals(order.getOrderType(), 2)) {
            seckillService.restoreAfterOrderCancel(order.getId());
            return;
        }
        restoreStockByOrder(order.getId());
    }

    private void registerDelayCloseAfterCommit(Long orderId, String orderSn) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orderDelaySender.sendDelayClose(new OrderDelayMessage(orderId, orderSn));
                }
            });
        } else {
            orderDelaySender.sendDelayClose(new OrderDelayMessage(orderId, orderSn));
        }
    }

    private String tokenKey(Long userId, String token) {
        return CacheConstants.ORDER_TOKEN_KEY_PREFIX + userId + ":" + token;
    }

    private OrderDetailVO toDetailVO(OmsOrder order, Map<Long, String> usernameMap) {
        OrderDetailVO vo = new OrderDetailVO();
        BeanUtils.copyProperties(order, vo);
        vo.setStatusDesc(OrderStatus.descOf(order.getStatus()));
        if (usernameMap != null) {
            vo.setUsername(usernameMap.get(order.getUserId()));
        }
        List<OrderItemVO> items = orderItemMapper.selectList(
                        Wrappers.<OmsOrderItem>lambdaQuery().eq(OmsOrderItem::getOrderId, order.getId())).stream()
                .map(this::toItemVO)
                .toList();
        vo.setItems(items);
        return vo;
    }

    private OrderItemVO toItemVO(OmsOrderItem item) {
        OrderItemVO vo = new OrderItemVO();
        BeanUtils.copyProperties(item, vo);
        return vo;
    }

    /**
     * 下单过程中的明细内部上下文
     */
    private record OrderItemContext(PmsSku sku, PmsSpu spu, Integer quantity, BigDecimal lineTotal) {
    }
}
