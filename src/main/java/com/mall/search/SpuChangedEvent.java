package com.mall.search;

/**
 * 商品变更事件（事务提交后异步同步到 ES）
 *
 * @author mall
 */
public record SpuChangedEvent(Long spuId, Type type) {

    public enum Type {
        /** 新增或更新：重新索引 */
        UPSERT,
        /** 删除或下架：移除索引 */
        DELETE
    }
}
