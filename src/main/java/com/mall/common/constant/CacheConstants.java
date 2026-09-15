package com.mall.common.constant;

/**
 * Redis Key 统一管理
 *
 * @author mall
 */
public final class CacheConstants {

    private CacheConstants() {
    }

    /** 商品详情缓存前缀，拼接 SPU ID */
    public static final String SPU_DETAIL_KEY = "cache:spu:detail:";

    /** 商品详情重建互斥锁前缀，拼接 SPU ID */
    public static final String SPU_DETAIL_LOCK_KEY = "lock:spu:detail:";

    /** 分类树缓存 */
    public static final String CATEGORY_TREE_KEY = "cache:category:tree";

    /** 空值占位符（空字符串），缓存不存在的商品以防缓存穿透 */
    public static final String NULL_PLACEHOLDER = "";

    /** 购物车 Hash key 前缀，拼接用户 ID；field 为 SKU ID，value 为购物车项 JSON */
    public static final String CART_KEY_PREFIX = "cart:user:";

    /** 下单幂等令牌 key 前缀，拼接 用户ID:令牌 */
    public static final String ORDER_TOKEN_KEY_PREFIX = "order:token:";

    /** 秒杀库存计数 key 前缀，拼接秒杀商品ID（预热时写入剩余可抢数量） */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    /** 一人一单 Redis 层快速判重集合 key 前缀，拼接秒杀商品ID，member 为用户ID */
    public static final String SECKILL_ORDERED_SET_KEY = "seckill:ordered:";

    /** 场次秒杀商品缓存 Hash key 前缀，拼接场次ID，field 为秒杀商品ID，value 为秒杀商品 JSON */
    public static final String SECKILL_SKUS_HASH_KEY = "seckill:skus:";

    /** 秒杀商品ID -> 场次ID 的索引 key 前缀，预热时写入，秒杀执行时用于定位缓存 Hash */
    public static final String SECKILL_SKU_SESSION_KEY = "seckill:sku:session:";

    /** 秒杀下单结果 key 前缀，拼接 用户ID:秒杀商品ID，值为订单号ID（成功）或 FAIL（失败） */
    public static final String SECKILL_RESULT_KEY = "seckill:result:";

    /** 当日场次列表缓存 key 前缀，拼接 yyyy-MM-dd */
    public static final String SECKILL_SESSIONS_CACHE_KEY = "cache:seckill:sessions:";

    /** 秒杀下单分布式锁前缀（Redisson 一人一单），拼接 用户ID:秒杀商品ID */
    public static final String SECKILL_LOCK_KEY = "lock:seckill:order:";

    /** 秒杀失败占位值（写入结果 key，供前端轮询感知） */
    public static final String SECKILL_RESULT_FAIL = "FAIL";

    /** 接口限流滑动窗口 ZSET key 前缀，拼接 请求URI:用户ID或IP（score=请求时间戳，member=请求唯一标识） */
    public static final String ACCESS_LIMIT_KEY = "access:limit:";

    /** 秒杀验证码 key 前缀，拼接验证码令牌，value 为算式答案（一次性校验，用后即删） */
    public static final String SECKILL_CAPTCHA_KEY = "seckill:captcha:";

    /** 秒杀动态下单路径 key 前缀，拼接 用户ID:秒杀商品ID，value 为服务端下发的 MD5 路径 */
    public static final String SECKILL_PATH_KEY = "seckill:path:";

    /**
     * JWT 黑名单 key 前缀，拼接 token 的 MD5 摘要。
     * 登出或 refresh 轮换时将 token 加入黑名单，TTL 取 token 剩余有效期，
     * 过期自动清理，避免黑名单无限膨胀。
     */
    public static final String JWT_BLACKLIST_KEY = "jwt:blacklist:";
}
