package com.mall.service;

import com.mall.common.page.PageResult;
import com.mall.dto.OrderCreateDTO;
import com.mall.dto.OrderPageQuery;
import com.mall.vo.OrderCreateVO;
import com.mall.vo.OrderDetailVO;
import com.mall.vo.OrderVO;

/**
 * 订单服务
 *
 * @author mall
 */
public interface OrderService {

    /**
     * 发放下单幂等令牌（写入 Redis，一次性消费）
     */
    String issueToken();

    /**
     * 创建订单：幂等令牌防重复提交 + 服务端重算价格 + 条件扣库存 + 事务提交后发延迟关单消息
     */
    OrderCreateVO createOrder(OrderCreateDTO dto);

    /**
     * 模拟支付（待支付 -> 已支付）
     */
    void pay(Long id);

    /**
     * 用户主动取消（待支付 -> 已取消，回补库存）
     */
    void cancel(Long id);

    /**
     * 管理端发货（已支付 -> 已发货）
     */
    void ship(Long id);

    /**
     * 确认收货（已发货 -> 已完成）
     */
    void confirm(Long id);

    /**
     * 当前用户订单详情（含归属校验）
     */
    OrderDetailVO detail(Long id);

    /**
     * 管理端订单详情（无归属限制）
     */
    OrderDetailVO adminDetail(Long id);

    /**
     * 当前用户订单分页
     */
    PageResult<OrderVO> myPage(OrderPageQuery query);

    /**
     * 管理端订单分页
     */
    PageResult<OrderVO> adminPage(OrderPageQuery query);

    /**
     * 超时未支付自动关单（MQ 消费者调用，CAS 幂等，回补库存）
     *
     * @param orderId   订单ID
     * @param messageId MQ 消息唯一ID，用于 mq_consume_log 消费幂等
     */
    void closeExpiredOrder(Long orderId, String messageId);
}
