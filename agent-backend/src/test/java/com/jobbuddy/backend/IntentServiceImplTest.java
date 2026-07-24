package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.chat.client.IntentClient;
import com.jobbuddy.backend.modules.chat.service.impl.IntentServiceImpl;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class IntentServiceImplTest {

  private static final String FALLBACK_MARKER = "intent_service_unavailable_local_fallback";

  @Test
  void shouldPreferRemoteIntentResultWhenAvailable() {
    IntentClient client = mock(IntentClient.class);
    IntentResult remote =
        new IntentResult(
            "job",
            "job.recommend",
            0.92,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs");
    remote.setRouter("llm");
    when(client.classify("找 Java 岗位")).thenReturn(remote);

    IntentServiceImpl service = new IntentServiceImpl(client);
    IntentResult result = service.classify("找 Java 岗位");

    assertEquals("job.recommend", result.getIntent());
    assertEquals("llm", result.getRouter());
    assertFalse(result.getSecondary().contains(FALLBACK_MARKER));
  }

  @Test
  void emptyMessageShouldFallbackToClarify() {
    IntentClient client = mock(IntentClient.class);
    when(client.classify("   ")).thenReturn(null);

    IntentResult result = new IntentServiceImpl(client).classify("   ");

    assertEquals("unknown", result.getIntent());
    assertTrue(result.isNeedsClarification());
    assertEquals("clarify", result.getNextAction());
    assertEquals("backend_fallback", result.getRouter());
    assertTrue(result.getSecondary().contains(FALLBACK_MARKER));
  }

  @Test
  void highRiskKeywordsShouldBeRejectedByLocalFallback() {
    IntentClient client = mock(IntentClient.class);
    when(client.classify("帮我 rm -rf 服务器上的所有文件")).thenReturn(null);

    IntentResult result = new IntentServiceImpl(client).classify("帮我 rm -rf 服务器上的所有文件");

    assertEquals("security", result.getDomain());
    assertEquals("high_risk_request", result.getIntent());
    assertEquals("reject", result.getNextAction());
    assertEquals("high", result.getRisk());
    assertEquals("backend_fallback", result.getRouter());
    assertTrue(result.isNeedsClarification());
  }

  @Test
  void jobKeywordsShouldRouteToJobDomainByLocalFallback() {
    IntentClient client = mock(IntentClient.class);
    when(client.classify("我想优化一下简历")).thenReturn(null);

    IntentResult result = new IntentServiceImpl(client).classify("我想优化一下简历");

    assertEquals("job", result.getDomain());
    assertEquals("job.consult", result.getIntent());
    assertEquals("direct_answer", result.getNextAction());
    assertEquals("backend_fallback", result.getRouter());
    assertTrue(result.getSecondary().contains(FALLBACK_MARKER));
  }

  @Test
  void generalMessageShouldFallbackToOpenDomainChat() {
    IntentClient client = mock(IntentClient.class);
    when(client.classify("今天天气怎么样")).thenReturn(null);

    IntentResult result = new IntentServiceImpl(client).classify("今天天气怎么样");

    assertEquals("open_domain", result.getDomain());
    assertEquals("general.chat", result.getIntent());
    assertEquals("backend_fallback", result.getRouter());
    assertFalse(result.isNeedsClarification());
    assertTrue(result.getSecondary().contains(FALLBACK_MARKER));
  }
}
