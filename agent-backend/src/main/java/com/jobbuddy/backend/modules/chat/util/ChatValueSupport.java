package com.jobbuddy.backend.modules.chat.util;

import java.util.Locale;
import java.util.Map;

/**
 * 聊天链路通用的取值与文本归一化纯函数：从无 schema 的 Map 中安全取值、做类型兜底转换。 提取为独立工具类以收敛 ChatSseServiceImpl
 * 体积，全部为无状态静态方法，行为与原私有方法一致。
 */
public final class ChatValueSupport {

  private ChatValueSupport() {}

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

  /** 从跨服务异常、嵌套错误对象或普通字符串中提取可展示消息，禁止把 Map 直接透传给前端。 */
  public static String errorMessage(Object error, String fallback) {
    return errorMessage(error, stringValue(fallback, "请求处理失败，请稍后重试。"), 0);
  }

  private static String errorMessage(Object error, String fallback, int depth) {
    if (error == null || depth > 4) return fallback;
    if (error instanceof Throwable) {
      Throwable throwable = (Throwable) error;
      String message = stringValue(throwable.getMessage());
      if (!message.isEmpty()) return message;
      return errorMessage(throwable.getCause(), fallback, depth + 1);
    }
    if (error instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) error;
      String[] keys = {"message", "detail", "summary", "error", "reason"};
      for (String key : keys) {
        Object value = map.get(key);
        if (value != null && value != error) {
          String message = errorMessage(value, "", depth + 1);
          if (!message.isEmpty()) return message;
        }
      }
      String code = stringValue(map.get("code"));
      return code.isEmpty() ? fallback : "请求处理失败（错误码：" + code + "）";
    }
    String message = stringValue(error);
    return message.isEmpty() || "[object Object]".equals(message) ? fallback : message;
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
