package com.mall.service;

import com.mall.dto.SeckillActivitySaveDTO;
import com.mall.dto.SeckillExecuteDTO;
import com.mall.dto.SeckillPathDTO;
import com.mall.dto.SeckillSessionSaveDTO;
import com.mall.dto.SeckillSkuSaveDTO;
import com.mall.entity.SmsSeckillActivity;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.vo.SeckillResultVO;
import com.mall.vo.SeckillSessionVO;
import com.mall.vo.SeckillSkuVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 秒杀服务
 * <p>
 * 下单链路：验证码（机器拦截/削峰）-> 动态 MD5 路径（接口隐藏）-> 接口限流（防连点/防刷）
 * -> 内存售罄标记 -> Redis 缓存校验 -> Lua 原子预减库存+一人一单判重
 * -> MQ 异步下单（削峰）-> 消费端 Redisson 一人一单 + DB 条件扣减 -> 订单落库 -> 结果 key 供轮询
 *
 * @author mall
 */
public interface SeckillService {

    // ============================== 用户端 ==============================

    /**
     * 指定日期的场次列表（缓存 TTL 短，保证开抢时间基本实时）
     */
    List<SeckillSessionVO> listSessions(LocalDate date);

    /**
     * 场次秒杀商品列表：优先读预热缓存，未预热时 DB 兜底
     */
    List<SeckillSkuVO> listSessionSkus(Long sessionId);

    /**
     * 校验一次性验证码并换取秒杀动态路径（MD5 地址，短 TTL）。
     * 动态路径把真实下单 URL 隐藏到开抢之后，防止活动前接口探测与脚本刷单。
     */
    String createSeckillPath(Long seckillSkuId, SeckillPathDTO dto);

    /**
     * 秒杀执行：动态路径校验 -> 三道闸门（内存标记/Lua 预减/MQ 异步），立即返回排队中
     */
    SeckillResultVO execute(Long seckillSkuId, String path, SeckillExecuteDTO dto);

    /**
     * 轮询秒杀结果：排队中 / 成功（含订单ID）/ 失败
     */
    SeckillResultVO result(Long seckillSkuId);

    // ============================== MQ 消费 ==============================

    /**
     * 消费秒杀下单消息：Redisson 一人一单 + DB 条件扣减 + 订单落库（事务）
     */
    void handleSeckillOrder(SeckillOrderMessage message);

    /**
     * 消费失败补偿：归还 Redis 预减库存、移除一人一单标记、写入失败结果
     */
    void compensateOnConsumeFailure(SeckillOrderMessage message);

    // ============================== 管理端 ==============================

    void createActivity(SeckillActivitySaveDTO dto);

    List<SmsSeckillActivity> listActivities();

    void createSession(SeckillSessionSaveDTO dto);

    void createSeckillSku(SeckillSkuSaveDTO dto);

    /**
     * 预热上架：场次商品信息与剩余库存写入 Redis，清除本地售罄标记
     */
    void uploadToRedis(Long seckillSkuId);

    // ============================== 订单联动 ==============================

    /**
     * 秒杀订单取消/超时关单后的库存回补（DB 秒杀库存 + Redis 计数 + 一人一单标记）
     */
    void restoreAfterOrderCancel(Long orderId);
}
