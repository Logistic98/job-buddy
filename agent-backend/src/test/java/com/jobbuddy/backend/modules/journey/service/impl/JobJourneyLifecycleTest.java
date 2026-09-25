package com.jobbuddy.backend.modules.journey.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.journey.dto.request.*;
import com.jobbuddy.backend.modules.journey.repository.JobJourneyRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobJourneyLifecycleTest {
  final JobJourneyRepository repository = mock(JobJourneyRepository.class);
  final JobBuddyProperties properties = new JobBuddyProperties();
  final JobJourneyServiceImpl service = new JobJourneyServiceImpl(repository, properties);
  final JsonCodec json = new JsonCodec();

  @Test
  void targetUpdatePreservesIdentityAndOptionalFields() {
    when(repository.findTarget("user"))
        .thenReturn(Map.of("targetId", "target-1", "userId", "user"));
    JobTargetRequest request = new JobTargetRequest();
    request.setLocation(" 上海 ");
    request.setPositions("Java");
    service.saveTarget("user", request);
    ArgumentCaptor<Map> saved = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveTarget(saved.capture());
    assertEquals("target-1", saved.getValue().get("targetId"));
    assertEquals(" 上海 ", saved.getValue().get("location"));
    assertEquals("Java", saved.getValue().get("positions"));
    assertEquals("", saved.getValue().get("notes"));
  }

  @Test
  void newTargetUsesConfiguredDefaultOwner() {
    properties.setDefaultUserId("local-user");
    when(repository.findTarget("local-user")).thenReturn(null);
    service.saveTarget(null, new JobTargetRequest());
    ArgumentCaptor<Map> saved = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveTarget(saved.capture());
    assertTrue(saved.getValue().get("targetId").toString().startsWith("target_"));
    assertEquals("local-user", saved.getValue().get("userId"));
  }

  @Test
  void recordUpdateNormalizesMixedTagsAndDeletionChecksOwner() {
    when(repository.findRecord("record"))
        .thenReturn(Map.of("recordId", "record", "userId", "user"));
    JourneyRecordRequest request = new JourneyRecordRequest();
    request.setCompany("Company");
    request.setTags(json.toTree(Arrays.asList(" Java ", Map.of("label", "AI"), " ", null)));
    service.saveRecord("user", request, "record");
    ArgumentCaptor<Map> saved = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveRecord(saved.capture());
    assertEquals(
        List.of(Map.of("label", "Java"), Map.of("label", "AI")), saved.getValue().get("tags"));
    service.deleteRecord("record", "user");
    verify(repository).deleteRecord("record");
    when(repository.findRecord("missing")).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> service.getRecord("missing", "user"));
    verify(repository, never()).deleteRecord("missing");
  }

  @Test
  void emptyFunnelHasZeroRatiosAndActionableMissingDataMessage() {
    when(repository.findTarget("user")).thenReturn(Map.of("domains", "AI"));
    var result = service.analyzeProgress("user", new JourneyAnalysisRequest());
    assertEquals(0, result.getMetrics().getTotal());
    assertTrue(result.getScoreGroups().stream().allMatch(group -> group.getScore() == 0));
    assertTrue(result.getSummary().contains("没有"));
    assertFalse(result.getRisks().isEmpty());
    assertTrue(result.getNextActions().stream().anyMatch(action -> action.contains("AI")));
    assertTrue(result.getFollowUpMessage().contains("跟进"));
  }

  @Test
  void selectionAndFocusLimitMetricsToRequestedRecords() {
    when(repository.findTarget("user")).thenReturn(Map.of("domains", "AI"));
    when(repository.listRecords("user", null, null, null))
        .thenReturn(
            List.of(
                Map.of("recordId", "excluded", "result", "通过", "status", "Offer"),
                Map.of(
                    "recordId", "failed", "result", "未通过", "company", "Failed Co", "priority", "高"),
                Map.of("recordId", "pending", "result", "跟进中", "company", "Pending Co")));
    JourneyAnalysisRequest request = new JourneyAnalysisRequest();
    request.setRecordIds(Arrays.asList("failed", "pending", "pending", " ", null));
    request.setRecordId("pending");
    var result = service.analyzeProgress("user", request);
    assertEquals(2, result.getMetrics().getTotal());
    assertEquals(1, result.getMetrics().getFailed());
    assertEquals(1, result.getMetrics().getPending());
    assertEquals(0, result.getMetrics().getOffer());
    assertTrue(result.getFollowUpMessage().contains("Pending Co"));
    assertTrue(result.getRisks().stream().anyMatch(risk -> risk.contains("未通过")));
  }
}
