package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.journey.repository.JobJourneyRepository;
import com.jobbuddy.backend.modules.journey.service.impl.JobJourneyServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobJourneyServiceCompatibilityTest {
    @Test
    void listRecordsIncludesLegacyDefaultUserRowsForAuthenticatedUser() {
        JobJourneyRepository repository = mock(JobJourneyRepository.class);
        Map<String, Object> legacy = record("journey_legacy", "default-user");
        when(repository.listRecords("user-auth-1", null, null, null)).thenReturn(Collections.<Map<String, Object>>emptyList());
        when(repository.listRecords("default-user", null, null, null)).thenReturn(Collections.singletonList(legacy));
        JobJourneyServiceImpl service = new JobJourneyServiceImpl(repository, new JobBuddyProperties());

        List<Map<String, Object>> rows = service.listRecords("user-auth-1", null, null, null);

        assertEquals(1, rows.size());
        assertEquals("journey_legacy", rows.get(0).get("recordId"));
    }

    @Test
    void targetFallsBackToLegacyDefaultUserTargetForAuthenticatedUser() {
        JobJourneyRepository repository = mock(JobJourneyRepository.class);
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        legacy.put("targetId", "target_legacy");
        legacy.put("userId", "default-user");
        when(repository.findTarget("user-auth-1")).thenReturn(null);
        when(repository.findTarget("default-user")).thenReturn(legacy);
        JobJourneyServiceImpl service = new JobJourneyServiceImpl(repository, new JobBuddyProperties());

        Map<String, Object> target = service.getTarget("user-auth-1");

        assertEquals("target_legacy", target.get("targetId"));
    }

    private Map<String, Object> record(String recordId, String userId) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("recordId", recordId);
        row.put("userId", userId);
        row.put("company", "Legacy Co");
        return row;
    }
}
