package com.mall.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 商品搜索参数（mall.search）
 *
 * @author mall
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.search")
public class SearchProperties {

    /**
     * 搜索总开关：false 时直接走数据库分页（本地无 ES 时可关闭）
     */
    private boolean enabled = true;

    /**
     * 商品索引名
     */
    private String indexName = "pms_spu";

    /**
     * 建索引分词器；IK 插件不可用时自动降级 standard
     */
    private String analyzer = "ik_max_word";

    /**
     * 查询分词器（粒度更细，召回更准）
     */
    private String searchAnalyzer = "ik_smart";

    /**
     * 脏数据补偿扫描间隔（毫秒）
     */
    private long compensateIntervalMs = 10000;

    /**
     * 搜索超时（毫秒），超时视为 ES 不可用并降级数据库
     */
    private long timeoutMs = 2000;
}
