package com.jobbuddy.backend.modules.chat.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ChatSessionCacheTest {

  @Test
  void shouldSkipRedisDuringFailureCooldown() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenThrow(new RuntimeException("redis timeout"));
    ChatSessionCache cache = new ChatSessionCache(redis, new JsonCodec());
    cache.setFailureCooldown(Duration.ofSeconds(30));

    assertNull(cache.get("s1"));
    assertNull(cache.get("s1"));

    verify(values, times(1)).get("job-buddy:chat-session:s1");
  }

  @Test
  void shouldRetryAndRecoverWhenCooldownExpires() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString()))
        .thenThrow(new RuntimeException("redis timeout"))
        .thenReturn(
            "{\"tenantId\":\"tenant-a\",\"userId\":\"user-a\",\"sessionId\":\"s1\",\"resumeId\":\"r1\",\"jobs\":[],\"toolEvents\":[],\"resumeMatch\":{}}");
    ChatSessionCache cache = new ChatSessionCache(redis, new JsonCodec());
    cache.setFailureCooldown(Duration.ZERO);

    assertNull(cache.get("s1"));
    ChatSessionState recovered = cache.get("s1");

    assertEquals("tenant-a", recovered.tenantId);
    assertEquals("user-a", recovered.userId);
    assertEquals("s1", recovered.sessionId);
    assertEquals("r1", recovered.resumeId);
    verify(values, times(2)).get("job-buddy:chat-session:s1");
  }
}
