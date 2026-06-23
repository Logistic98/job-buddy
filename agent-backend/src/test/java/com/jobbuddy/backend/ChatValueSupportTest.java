package com.jobbuddy.backend;

import com.jobbuddy.backend.modules.chat.util.ChatValueSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatValueSupportTest {

    @Test
    void firstPresentShouldReturnFirstNonBlankValue() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", "  ");
        map.put("b", "value");
        assertEquals("value", ChatValueSupport.firstPresent(map, "a", "b"));
        assertNull(ChatValueSupport.firstPresent(map, "missing"));
        assertNull(ChatValueSupport.firstPresent(null, "a"));
    }

    @Test
    void stringValueShouldTrimAndFallback() {
        assertEquals("x", ChatValueSupport.stringValue("  x  "));
        assertEquals("", ChatValueSupport.stringValue(null));
        assertEquals("fallback", ChatValueSupport.stringValue("", "fallback"));
        assertEquals("y", ChatValueSupport.stringValue(" y ", "fallback"));
    }

    @Test
    void doubleValueShouldParseNumbersAndFallback() {
        assertEquals(1.5, ChatValueSupport.doubleValue("1.5", 0), 1e-9);
        assertEquals(2.0, ChatValueSupport.doubleValue(2, 0), 1e-9);
        assertEquals(9.0, ChatValueSupport.doubleValue("abc", 9), 1e-9);
    }

    @Test
    void booleanValueShouldParseTokensAndFallback() {
        assertTrue(ChatValueSupport.booleanValue("true", false));
        assertFalse(ChatValueSupport.booleanValue("false", true));
        assertTrue(ChatValueSupport.booleanValue(Boolean.TRUE, false));
        assertTrue(ChatValueSupport.booleanValue("unknown", true));
    }

    @Test
    void truncateShouldLimitLength() {
        assertEquals("ab...", ChatValueSupport.truncate("abcdef", 2));
        assertEquals("abc", ChatValueSupport.truncate("abc", 5));
        assertNull(ChatValueSupport.truncate(null, 5));
    }
}
