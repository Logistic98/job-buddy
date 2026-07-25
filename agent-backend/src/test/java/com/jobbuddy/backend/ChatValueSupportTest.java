package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobbuddy.backend.modules.chat.util.ChatValueSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 ChatValueSupport 的核心行为、异常路径与边界条件。
 */
class ChatValueSupportTest {

  /**
   * 验证 ChatValueSupport 的输入校验与拒绝边界。
   */
  @Test
  void firstPresentShouldReturnFirstNonBlankValue() {
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    map.put("a", "  ");
    map.put("b", "value");
    assertEquals("value", ChatValueSupport.firstPresent(map, "a", "b"));
    assertNull(ChatValueSupport.firstPresent(map, "missing"));
    assertNull(ChatValueSupport.firstPresent(null, "a"));
  }

  /**
   * 验证 ChatValueSupport 的失败恢复、超时与降级边界。
   */
  @Test
  void stringValueShouldTrimAndFallback() {
    assertEquals("x", ChatValueSupport.stringValue("  x  "));
    assertEquals("", ChatValueSupport.stringValue(null));
    assertEquals("fallback", ChatValueSupport.stringValue("", "fallback"));
    assertEquals("y", ChatValueSupport.stringValue(" y ", "fallback"));
  }

  /**
   * 验证 ChatValueSupport 的失败恢复、超时与降级边界。
   */
  @Test
  void errorMessageShouldExtractNestedMessageAndFallbackForEmptyObjects() {
    Map<String, Object> nested = new LinkedHashMap<String, Object>();
    nested.put("message", "工具 resume_match 执行超时（125 秒）");
    Map<String, Object> error = new LinkedHashMap<String, Object>();
    error.put("error", nested);

    assertEquals("工具 resume_match 执行超时（125 秒）", ChatValueSupport.errorMessage(error, "fallback"));
    assertEquals("fallback", ChatValueSupport.errorMessage(new LinkedHashMap<>(), "fallback"));
    assertEquals("fallback", ChatValueSupport.errorMessage(new RuntimeException(), "fallback"));
  }

  /**
   * 验证 ChatValueSupport 的失败恢复、超时与降级边界。
   */
  @Test
  void doubleValueShouldParseNumbersAndFallback() {
    assertEquals(1.5, ChatValueSupport.doubleValue("1.5", 0), 1e-9);
    assertEquals(2.0, ChatValueSupport.doubleValue(2, 0), 1e-9);
    assertEquals(9.0, ChatValueSupport.doubleValue("abc", 9), 1e-9);
  }

  /**
   * 验证 ChatValueSupport 的失败恢复、超时与降级边界。
   */
  @Test
  void booleanValueShouldParseTokensAndFallback() {
    assertTrue(ChatValueSupport.booleanValue("true", false));
    assertFalse(ChatValueSupport.booleanValue("false", true));
    assertTrue(ChatValueSupport.booleanValue(Boolean.TRUE, false));
    assertTrue(ChatValueSupport.booleanValue("unknown", true));
  }

  /**
   * 验证 ChatValueSupport 的数量、长度与分页边界。
   */
  @Test
  void truncateShouldLimitLength() {
    assertEquals("ab...", ChatValueSupport.truncate("abcdef", 2));
    assertEquals("abc", ChatValueSupport.truncate("abc", 5));
    assertNull(ChatValueSupport.truncate(null, 5));
  }
}
