package com.mall.search;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.page.PageResult;
import com.mall.entity.PmsCategory;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.dto.SpuPageQuery;
import com.mall.mapper.PmsCategoryMapper;
import com.mall.mapper.PmsSkuMapper;
import com.mall.service.PmsSpuService;
import com.mall.vo.SpuPageVO;
import com.mall.vo.SpuSearchPageVO;
import com.mall.vo.SpuSearchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品搜索服务：ES 关键词/分类/价格区间/排序/高亮，ES 异常自动降级数据库分页。
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ElasticsearchOperations operations;
    private final SearchProperties properties;
    private final SearchIndexManager indexManager;
    private final PmsSpuService spuService;
    private final PmsSkuMapper skuMapper;
    private final PmsCategoryMapper categoryMapper;

    /**
     * 商品搜索；ES 不可用或关闭时降级 DB，返回 degraded=true
     */
    public SpuSearchPageVO search(SpuSearchQuery query) {
        long start = System.currentTimeMillis();
        normalize(query);
        if (!properties.isEnabled()) {
            return degrade(query, start);
        }
        try {
            Criteria criteria = new Criteria("status").is(1);
            String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;
            if (keyword != null) {
                criteria = criteria.and(new Criteria("name").matches(keyword));
            }
            if (query.getCategoryId() != null) {
                criteria = criteria.and(new Criteria("category_id").is(query.getCategoryId()));
            }
            if (query.getMinPrice() != null) {
                criteria = criteria.and(new Criteria("min_price").greaterThanEqual(query.getMinPrice().doubleValue()));
            }
            if (query.getMaxPrice() != null) {
                criteria = criteria.and(new Criteria("min_price").lessThanEqual(query.getMaxPrice().doubleValue()));
            }

            CriteriaQuery searchQuery = new CriteriaQuery(criteria,
                    PageRequest.of((int) (query.getPageNum() - 1), (int) (long) query.getPageSize(),
                            buildSort(query.getSort())));
            if (keyword != null) {
                HighlightParameters parameters = HighlightParameters.builder()
                        .withPreTags("<em>")
                        .withPostTags("</em>")
                        .build();
                Highlight highlight = new Highlight(parameters, List.of(new HighlightField("name")));
                searchQuery.setHighlightQuery(new HighlightQuery(highlight, SpuSearchDoc.class));
            }

            SearchHits<SpuSearchDoc> hits = operations.search(searchQuery, SpuSearchDoc.class,
                    IndexCoordinates.of(properties.getIndexName()));
            List<SpuSearchVO> list = hits.getSearchHits().stream()
                    .map(this::toVO)
                    .toList();
            long total = hits.getTotalHits();
            PageResult<SpuSearchVO> page = new PageResult<>();
            page.setPageNum(query.getPageNum());
            page.setPageSize(query.getPageSize());
            page.setTotal(total);
            page.setPages((total + query.getPageSize() - 1) / query.getPageSize());
            page.setList(list);
            return SpuSearchPageVO.of(page, false, System.currentTimeMillis() - start,
                    indexManager.getActiveAnalyzer());
        } catch (Exception e) {
            log.warn("ES 搜索失败，降级数据库: {}", e.getMessage());
            return degrade(query, start);
        }
    }

    /**
     * 全量重建索引（仅上架商品），返回索引文档数
     */
    public long reindexAll() {
        if (!properties.isEnabled()) {
            throw new BusinessException("商品搜索已关闭，无法重建索引");
        }
        String analyzer = indexManager.ensureIndex(true);
        List<PmsSpu> spus = spuService.list(Wrappers.<PmsSpu>lambdaQuery()
                .eq(PmsSpu::getStatus, 1)
                .orderByAsc(PmsSpu::getId));
        if (spus.isEmpty()) {
            return 0;
        }
        Set<Long> categoryIds = spus.stream().map(PmsSpu::getCategoryId).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(PmsCategory::getId, PmsCategory::getName));

        List<Long> spuIds = spus.stream().map(PmsSpu::getId).toList();
        Map<Long, BigDecimal> minPriceMap = skuMapper.selectList(Wrappers.<PmsSku>lambdaQuery()
                        .in(PmsSku::getSpuId, spuIds)
                        .eq(PmsSku::getStatus, 1)).stream()
                .collect(Collectors.groupingBy(PmsSku::getSpuId,
                        Collectors.mapping(PmsSku::getPrice, Collectors.minBy(BigDecimal::compareTo))))
                .entrySet().stream()
                .filter(e -> e.getValue().isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

        List<SpuSearchDoc> docs = spus.stream()
                .map(spu -> toDoc(spu, categoryNameMap.get(spu.getCategoryId()), minPriceMap.get(spu.getId())))
                .toList();
        operations.save(docs, IndexCoordinates.of(properties.getIndexName()));
        operations.indexOps(IndexCoordinates.of(properties.getIndexName())).refresh();
        log.info("商品索引全量重建完成 docs={} analyzer={}", docs.size(), analyzer);
        return docs.size();
    }

    /**
     * 单商品索引同步：上架则写入，下架/不存在则删除
     */
    public void indexSpu(Long spuId) {
        if (!properties.isEnabled()) {
            return;
        }
        PmsSpu spu = spuService.getById(spuId);
        if (spu == null || spu.getStatus() == null || spu.getStatus() != 1) {
            deleteSpu(spuId);
            return;
        }
        PmsCategory category = categoryMapper.selectById(spu.getCategoryId());
        BigDecimal minPrice = skuMapper.selectList(Wrappers.<PmsSku>lambdaQuery()
                        .eq(PmsSku::getSpuId, spuId)
                        .eq(PmsSku::getStatus, 1)).stream()
                .map(PmsSku::getPrice)
                .min(BigDecimal::compareTo)
                .orElse(null);
        operations.save(toDoc(spu, category == null ? null : category.getName(), minPrice),
                IndexCoordinates.of(properties.getIndexName()));
    }

    public void deleteSpu(Long spuId) {
        if (!properties.isEnabled()) {
            return;
        }
        operations.delete(String.valueOf(spuId), IndexCoordinates.of(properties.getIndexName()));
    }

    // ============================== 私有方法 ==============================

    private void normalize(SpuSearchQuery query) {
        if (query.getPageNum() == null || query.getPageNum() < 1) {
            query.setPageNum(1L);
        }
        if (query.getPageSize() == null || query.getPageSize() < 1) {
            query.setPageSize(10L);
        }
        // 限制单页最大条数，避免深分页拖垮 ES
        if (query.getPageSize() > 100) {
            query.setPageSize(100L);
        }
    }

    private Sort buildSort(String sort) {
        if ("priceAsc".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Order.asc("min_price"));
        }
        if ("priceDesc".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Order.desc("min_price"));
        }
        return Sort.unsorted();
    }

    private SpuSearchVO toVO(SearchHit<SpuSearchDoc> hit) {
        SpuSearchDoc doc = hit.getContent();
        SpuSearchVO vo = new SpuSearchVO();
        vo.setId(doc.getId());
        vo.setName(doc.getName());
        vo.setSubtitle(doc.getSubtitle());
        vo.setMainImage(doc.getMainImage());
        vo.setCategoryId(doc.getCategoryId());
        vo.setCategoryName(doc.getCategoryName());
        vo.setMinPrice(doc.getMinPrice() == null ? null : BigDecimal.valueOf(doc.getMinPrice()));
        vo.setStatus(doc.getStatus());
        List<String> highlights = hit.getHighlightField("name");
        if (highlights != null && !highlights.isEmpty()) {
            vo.setHighlightName(String.join("", highlights));
        }
        return vo;
    }

    private SpuSearchDoc toDoc(PmsSpu spu, String categoryName, BigDecimal minPrice) {
        SpuSearchDoc doc = new SpuSearchDoc();
        doc.setId(spu.getId());
        doc.setName(spu.getName());
        doc.setSubtitle(spu.getSubtitle());
        doc.setMainImage(spu.getMainImage());
        doc.setCategoryId(spu.getCategoryId());
        doc.setCategoryName(categoryName);
        doc.setMinPrice(minPrice == null ? null : minPrice.doubleValue());
        doc.setStatus(spu.getStatus());
        doc.setUpdateTime(spu.getUpdateTime() == null
                ? System.currentTimeMillis()
                : spu.getUpdateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        return doc;
    }

    /**
     * ES 降级：走 DB 分页（仅支持关键词/分类，价格区间在降级路径忽略）
     */
    private SpuSearchPageVO degrade(SpuSearchQuery query, long start) {
        SpuPageQuery dbQuery = new SpuPageQuery();
        dbQuery.setPageNum(query.getPageNum());
        dbQuery.setPageSize(query.getPageSize());
        dbQuery.setKeyword(query.getKeyword());
        dbQuery.setCategoryId(query.getCategoryId());
        PageResult<SpuPageVO> dbPage = spuService.pageOnSale(dbQuery);
        List<SpuSearchVO> list = dbPage.getList() == null ? Collections.emptyList()
                : dbPage.getList().stream().map(this::toVOFromPage).toList();
        PageResult<SpuSearchVO> page = new PageResult<>();
        page.setPageNum(dbPage.getPageNum());
        page.setPageSize(dbPage.getPageSize());
        page.setTotal(dbPage.getTotal());
        page.setPages(dbPage.getPages());
        page.setList(list);
        return SpuSearchPageVO.of(page, true, System.currentTimeMillis() - start, "standard");
    }

    private SpuSearchVO toVOFromPage(SpuPageVO spu) {
        SpuSearchVO vo = new SpuSearchVO();
        vo.setId(spu.getId());
        vo.setName(spu.getName());
        vo.setSubtitle(spu.getSubtitle());
        vo.setMainImage(spu.getMainImage());
        vo.setCategoryId(spu.getCategoryId());
        vo.setCategoryName(spu.getCategoryName());
        vo.setMinPrice(spu.getMinPrice());
        vo.setStatus(spu.getStatus());
        return vo;
    }
}
