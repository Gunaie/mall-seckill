package com.mall.search;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 商品搜索文档（索引 pms_spu）
 *
 * <p>索引 mapping 由 {@link SearchIndexManager} 动态创建，以便 IK 不可用时降级 standard 分词。</p>
 *
 * @author mall
 */
@Data
@Document(indexName = "pms_spu", createIndex = false)
public class SpuSearchDoc {

    @Id
    private Long id;

    /**
     * 商品名称（IK 分词，支持高亮）
     */
    @Field(name = "name", type = FieldType.Text)
    private String name;

    @Field(name = "subtitle", type = FieldType.Text)
    private String subtitle;

    @Field(name = "main_image", type = FieldType.Keyword, index = false)
    private String mainImage;

    @Field(name = "category_id", type = FieldType.Long)
    private Long categoryId;

    @Field(name = "category_name", type = FieldType.Keyword)
    private String categoryName;

    /**
     * 起售价（最低 SKU 价格）
     */
    @Field(name = "min_price", type = FieldType.Double)
    private Double minPrice;

    @Field(name = "status", type = FieldType.Integer)
    private Integer status;

    /**
     * 更新时间戳（毫秒），避免日期格式序列化差异
     */
    @Field(name = "update_time", type = FieldType.Long)
    private Long updateTime;
}
