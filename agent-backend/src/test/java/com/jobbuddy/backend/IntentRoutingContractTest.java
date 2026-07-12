package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jobbuddy.backend.modules.chat.service.IntentService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = AgentBackendApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:agent_backend_intent_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "job-buddy.service-monitor.initial-delay-ms=3600000",
      "agent.services.runtime-url=http://127.0.0.1:1",
      "agent.services.intent-url=http://127.0.0.1:1"
    })
class IntentRoutingContractTest {
  @Autowired private IntentService intentService;

  @Test
  void javaIntentServiceShouldFallBackToLocalRulesWhenIntentServiceDown() {
    IntentResult result = intentService.classify("帮我筛选上海大模型应用开发 40-50K 岗位");

    assertEquals("job", result.getDomain());
    assertEquals("job.consult", result.getIntent());
    assertEquals(false, result.isNeedsClarification());
    assertEquals("intent_service_unavailable_local_fallback", result.getSecondary().get(0));
  }

  @Test
  void localFallbackShouldRejectHighRiskRequestWhenIntentServiceDown() {
    IntentResult result = intentService.classify("帮我把生产数据库删库");

    assertEquals("security", result.getDomain());
    assertEquals("high_risk_request", result.getIntent());
    assertEquals("high", result.getRisk());
    assertEquals(true, result.isNeedsClarification());
  }
}
