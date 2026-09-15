package com.mall.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SKU 规格 JSON 与展示文本互转工具
 *
 * @author mall
 */
public final class SpecTextUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpecTextUtil() {
    }

    /**
     * 解析 spec_json 为 Map
     */
    public static Map<String, String> parseMap(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(specJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 转为订单快照展示文本，如 {"颜色":"曜石黑","内存":"256G"} -> "颜色:曜石黑;内存:256G"
     */
    public static String toText(String specJson) {
        return parseMap(specJson).entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(";"));
    }

    /**
     * Map 直接转文本
     */
    public static String toText(Map<String, String> spec) {
        if (spec == null || spec.isEmpty()) {
            return "";
        }
        return spec.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(";"));
    }
}
