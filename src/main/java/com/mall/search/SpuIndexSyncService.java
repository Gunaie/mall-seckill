package com.mall.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * 商品索引增量同步：事务提交后异步写入 ES，失败进 Redis 脏集合，定时补偿。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpuIndexSyncService {

    /**
     * 索引同步失败的 SPU ID 集合（下架/删除同样收集，补偿时按当前状态判断）
     */
    private static final String DIRTY_KEY = "search:dirty:spu";

    private final ProductSearchService searchService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 事务提交后异步同步；fallbackExecution=true 保证无事务调用（如手动触发）也能执行
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSpuChanged(SpuChangedEvent event) {
        apply(event);
    }

    /**
     * 定时补偿：重试脏集合中的商品索引
     */
    @Scheduled(fixedDelayString = "${mall.search.compensate-interval-ms:10000}")
    public void compensate() {
        Set<String> dirtyIds = stringRedisTemplate.opsForSet().members(DIRTY_KEY);
        if (dirtyIds == null || dirtyIds.isEmpty()) {
            return;
        }
        for (String id : dirtyIds) {
            try {
                searchService.indexSpu(Long.valueOf(id));
                stringRedisTemplate.opsForSet().remove(DIRTY_KEY, id);
            } catch (Exception e) {
                log.warn("商品索引补偿失败，稍后重试 spuId={} reason={}", id, e.getMessage());
                return;
            }
        }
        log.info("商品索引补偿完成，处理脏数据 {} 条", dirtyIds.size());
    }

    private void apply(SpuChangedEvent event) {
        try {
            if (event.type() == SpuChangedEvent.Type.DELETE) {
                searchService.deleteSpu(event.spuId());
            } else {
                searchService.indexSpu(event.spuId());
            }
            stringRedisTemplate.opsForSet().remove(DIRTY_KEY, String.valueOf(event.spuId()));
        } catch (Exception e) {
            // ES 故障不影响主流程：记录脏数据，等待补偿
            stringRedisTemplate.opsForSet().add(DIRTY_KEY, String.valueOf(event.spuId()));
            log.warn("商品索引同步失败，已加入补偿集合 spuId={} reason={}", event.spuId(), e.getMessage());
        }
    }
}
