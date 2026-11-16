package com.mall.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 搜索索引管理：启动时确保索引存在，IK 不可用时自动降级 standard 分词。
 *
 * <p>索引 mapping 手动创建而非依赖注解，目的是在 IK 插件缺失时仍能建索引并保持应用可用。</p>
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexManager implements ApplicationRunner {

    private static final String MAPPING_TEMPLATE = """
            {
              "properties": {
                "name":          {"type": "text", "analyzer": "%s", "search_analyzer": "%s"},
                "subtitle":      {"type": "text", "analyzer": "standard"},
                "main_image":    {"type": "keyword", "index": false},
                "category_id":   {"type": "long"},
                "category_name": {"type": "keyword"},
                "min_price":     {"type": "double"},
                "status":        {"type": "integer"},
                "update_time":   {"type": "long"}
              }
            }
            """;

    private final ElasticsearchOperations operations;
    private final SearchProperties properties;

    /**
     * 实际生效的分词器（IK 缺失时为 standard）
     */
    private volatile String activeAnalyzer = "standard";

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("商品搜索已关闭（mall.search.enabled=false）");
            return;
        }
        try {
            ensureIndex(false);
        } catch (Exception e) {
            // ES 未就绪不影响应用启动，搜索接口会降级走数据库
            log.warn("ES 索引初始化失败，搜索将降级数据库: {}", e.getMessage());
        }
    }

    /**
     * 确保索引存在；recreate=true 时先删除重建（全量重建入口）
     *
     * @return 实际生效的分词器
     */
    public synchronized String ensureIndex(boolean recreate) {
        IndexCoordinates coordinates = IndexCoordinates.of(properties.getIndexName());
        IndexOperations indexOps = operations.indexOps(coordinates);
        if (recreate && indexOps.exists()) {
            indexOps.delete();
            log.info("已删除旧搜索索引 {}", properties.getIndexName());
        }
        if (indexOps.exists()) {
            // 多实例场景：索引可能由其他实例创建，从 mapping 读取实际分词器
            readAnalyzerFromMapping(indexOps);
            return activeAnalyzer;
        }
        try {
            createIndex(indexOps, properties.getAnalyzer(), properties.getSearchAnalyzer());
            activeAnalyzer = properties.getAnalyzer();
            log.info("搜索索引创建完成 index={} analyzer={}", properties.getIndexName(), activeAnalyzer);
        } catch (Exception e) {
            log.warn("IK 分词不可用（{}），降级 standard 分词重建索引", e.getMessage());
            if (indexOps.exists()) {
                indexOps.delete();
            }
            createIndex(indexOps, "standard", "standard");
            activeAnalyzer = "standard";
            log.info("搜索索引创建完成 index={} analyzer=standard", properties.getIndexName());
        }
        return activeAnalyzer;
    }

    public String getActiveAnalyzer() {
        return activeAnalyzer;
    }

    private void createIndex(IndexOperations indexOps, String analyzer, String searchAnalyzer) {
        // 单节点部署，副本数设为 0，避免集群长期停留在 yellow
        indexOps.create(Map.of("number_of_replicas", 0));
        Document mapping = Document.parse(MAPPING_TEMPLATE.formatted(analyzer, searchAnalyzer));
        indexOps.putMapping(mapping);
    }

    /**
     * 从已有索引 mapping 读取 name 字段分词器，保证多实例上报的 analyzer 一致
     */
    private void readAnalyzerFromMapping(IndexOperations indexOps) {
        try {
            if (!(indexOps.getMapping().get("properties") instanceof Map<?, ?> properties)) {
                return;
            }
            if (properties.get("name") instanceof Map<?, ?> nameField
                    && nameField.get("analyzer") != null) {
                activeAnalyzer = nameField.get("analyzer").toString();
            }
        } catch (Exception e) {
            log.debug("读取索引 mapping 失败: {}", e.getMessage());
        }
    }
}
