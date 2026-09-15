package com.mall.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求工具：客户端 IP 解析等
 *
 * @author mall
 */
public final class RequestUtils {

    private RequestUtils() {
    }

    /**
     * 获取客户端真实 IP：优先解析反向代理头（X-Forwarded-For 第一个非 unknown 地址），
     * 兜底 X-Real-IP / remoteAddr。本地直连访问时返回 IPv6 回环 0:0:0:0:0:0:0:1。
     */
    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (isValid(ip)) {
            // 多级代理时第一个为客户端原始 IP
            int comma = ip.indexOf(',');
            return (comma > 0 ? ip.substring(0, comma) : ip).trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (isValid(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean isValid(String ip) {
        return ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip.trim());
    }
}
