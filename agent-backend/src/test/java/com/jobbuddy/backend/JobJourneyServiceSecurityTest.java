package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyAnalysisRequest;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyRecordRequest;
import com.jobbuddy.backend.modules.journey.repository.JobJourneyRepository;
import com.jobbuddy.backend.modules.journey.service.impl.JobJourneyServiceImpl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobJourneyServiceSecurityTest {
  private static final JsonCodec JSON = new JsonCodec();

  @Test
  void listRecordsExcludeLocalDefaultUserRowsForAuthenticatedUser() {
    JobJourneyRepository repository = mock(JobJourneyRepository.class);
    Map<String, Object> localRecord = record("journey_local", "default-user");
    when(repository.listRecords("user-auth-1", null, null, null))
        .thenReturn(Collections.<Map<String, Object>>emptyList());
    when(repository.listRecords("default-user", null, null, null))
        .thenReturn(Collections.singletonList(localRecord));
    JobJourneyServiceImpl service = new JobJourneyServiceImpl(repository, new JobBuddyProperties());

    List<Map<String, Object>> rows =
        service.listRecords("user-auth-1", null, null, null).stream().map(JSON::toMap).toList();

    assertEquals(0, rows.size());
    verify(repository, never()).listRecords("default-user", null, null, null);
  }

  @Test
  void newRecordGetsGeneratedIdAndCrossUserOperationsAreRejected() {
    JobJourneyRepository repository = mock(JobJourneyRepository.class);
    when(repository.findRecord(anyString())).thenReturn(record("journey_saved", "user-auth-1"));
    JobJourneyServiceImpl service = new JobJourneyServiceImpl(repository, new JobBuddyProperties());

    service.saveRecord(
        "user-auth-1",
        JSON.convert(
            Collections.<String, Object>singletonMap("company", "Test Co"),
            JourneyRecordRequest.class),
        null);
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveRecord(captor.capture());
    String generated = String.valueOf(captor.getValue().get("recordId"));
    assertFalse(generated.isEmpty());
    assertFalse("null".equals(generated));

    when(repository.findRecord("journey_other")).thenReturn(record("journey_other", "other-user"));
    assertThrows(
        IllegalArgumentException.class, () -> service.getRecord("journey_other", "user-auth-1"));
    assertThrows(
        IllegalArgumentException.class, () -> service.deleteRecord("journey_other", "user-auth-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.saveRecord("user-auth-1", new JourneyRecordRequest(), "journey_other"));
  }

  @Test
  void mapperXmlUsesTopLevelMapFieldsForJourneyWrites() throws Exception {
    String xml =
        new String(
            getClass().getResourceAsStream("/mapper/journey/JobJourneyMapper.xml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(xml.contains("#{target."));
    assertFalse(xml.contains("#{record."));
  }

  @Test
  void targetDoesNotFallBackToLocalDefaultUserForAuthenticatedUser() {
    JobJourneyRepository repository = mock(JobJourneyRepository.class);
    Map<String, Object> localTarget = new LinkedHashMap<String, Object>();
    localTarget.put("targetId", "target_local");
    localTarget.put("userId", "default-user");
    when(repository.findTarget("user-auth-1")).thenReturn(null);
    when(repository.findTarget("default-user")).thenReturn(localTarget);
    JobJourneyServiceImpl service = new JobJourneyServiceImpl(repository, new JobBuddyProperties());

    service.getTarget("user-auth-1");

    ArgumentCaptor<Map<String, Object>> targetCaptor = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveTarget(targetCaptor.capture());
    assertEquals("user-auth-1", targetCaptor.getValue().get("userId"));
    verify(repository, never()).findTarget("default-user");
  }

  @Test
  @SuppressWarnings("unchecked")
  void analysisReturnsExplainableScoreGroups() {
    JobJourneyRepository repository = mock(JobJourneyRepository.class);
    Map<String, Object> passed = record("journey_passed", "user-auth-1");
    passed.put("result", "通过");
    passed.put("status", "Offer");
    passed.put("interviewContent", "Agent 系统设计");
    passed.put("reflection", "需要加强可观测性表达");
    passed.put("nextAction", "准备终面");
    Map<String, Object> pending = record("journey_pending", "user-auth-1");
    pending.put("result", "待反馈");
    pending.put("status", "面试进展");
    when(repository.listRecords("user-auth-1", null, null, null))
        .thenReturn(java.util.Arrays.asList(passed, pending));
    when(repository.findTarget("user-auth-1")).thenReturn(target("user-auth-1"));
    JobJourneyServiceImpl service = new JobJourneyServiceImpl(repository, new JobBuddyProperties());

    Map<String, Object> analysis =
        JSON.toMap(service.analyzeProgress("user-auth-1", new JourneyAnalysisRequest()));
    List<Map<String, Object>> scoreGroups = (List<Map<String, Object>>) analysis.get("scoreGroups");

    assertEquals(4, scoreGroups.size());
    assertEquals("推进活跃度", scoreGroups.get(0).get("label"));
    assertEquals(100, scoreGroups.get(0).get("score"));
    assertEquals(50, scoreGroups.get(1).get("score"));
    assertEquals(50, scoreGroups.get(2).get("score"));
    assertEquals(50, scoreGroups.get(3).get("score"));
    assertTrue(
        scoreGroups.stream()
            .allMatch(
                group ->
                    ((Integer) group.get("score")) >= 0 && ((Integer) group.get("score")) <= 100));
  }

  private Map<String, Object> target(String userId) {
    Map<String, Object> target = new LinkedHashMap<String, Object>();
    target.put("targetId", "target_test");
    target.put("userId", userId);
    target.put("domains", "AI Agent");
    return target;
  }

  private Map<String, Object> record(String recordId, String userId) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("recordId", recordId);
    row.put("userId", userId);
    row.put("company", "Sample Co");
    return row;
  }
}
