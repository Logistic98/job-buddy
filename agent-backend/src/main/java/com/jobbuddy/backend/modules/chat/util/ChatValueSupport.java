package com.jobbuddy.backend.modules.chat.util;

import java.util.Locale;
import java.util.Map;

/**
 * 聊天链路通用的取值与文本归一化纯函数：从无 schema 的 Map 中安全取值、做类型兜底转换。
 * 提取为独立工具类以收敛 ChatSseServiceImpl 体积，全部为无状态静态方法，行为与原私有方法一致。
 */
public final class ChatValueSupport {

    private ChatValueSupport() {
    }

    public static Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        }
        return null;
    }

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static String stringValue(Object value, String fallback) {
        String text = stringValue(value);
        return text.isEmpty() ? fallback : text;
    }

    public static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException ignored) {
            // 非数值文本回退到默认值属预期，不需要告警。
            return fallback;
        }
    }

    public static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        return fallback;
    }

    public static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, Math.max(0, limit)) + "...";
    }
}
