package com.jobbuddy.backend;

import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.auth.service.impl.BossAuthServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BossAuthServiceImplTest {

    @Test
    void restorePersistedLoginStateShouldWriteCredentialWithoutQr() {
        BossCliService bossCli = mock(BossCliService.class);
        AuthStateRepository repository = mock(AuthStateRepository.class);
        when(repository.findByProvider("jackwener/boss-cli")).thenReturn(savedState("logged_in", "{\"cookies\":{}}"));

        BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);
        service.restorePersistedLoginState();

        verify(bossCli).writeCredential("{\"cookies\":{}}");
        verify(bossCli, never()).qrStart();
    }

    @Test
    void loginStatusShouldUseCacheWithinTtlAndAvoidRepeatedStatusCalls() {
        BossCliService bossCli = mock(BossCliService.class);
        AuthStateRepository repository = mock(AuthStateRepository.class);
        when(bossCli.hasLocalCredential()).thenReturn(true);
        when(bossCli.status()).thenReturn(status("logged_in", true));
        when(bossCli.readCredentialJson()).thenReturn("{\"cookies\":{}}");

        BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);
        Map<String, Object> first = service.loginStatus("s1", null);
        Map<String, Object> second = service.loginStatus("s1", null);

        assertTrue(Boolean.TRUE.equals(first.get("ok")));
        assertTrue(Boolean.TRUE.equals(second.get("ok")));
        verify(bossCli, times(1)).status();
        verify(repository, times(1)).save(eq("jackwener/boss-cli"), eq("logged_in"), eq("{\"cookies\":{}}"), any(Map.class));
    }

    @Test
    void invalidStatusShouldUpdateRepositoryAndRequireQr() {
        BossCliService bossCli = mock(BossCliService.class);
        AuthStateRepository repository = mock(AuthStateRepository.class);
        when(bossCli.hasLocalCredential()).thenReturn(true);
        when(bossCli.status()).thenReturn(status("auth_required", false));

        BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);
        Map<String, Object> response = service.loginStatus("s1", null);

        assertFalse(Boolean.TRUE.equals(response.get("ok")));
        assertEquals("auth_required", response.get("status"));
        verify(repository).updateStatus(eq("jackwener/boss-cli"), eq("auth_required"), any(Map.class));
    }

    @Test
    void qrLoggedInShouldPersistCredentialAndClearAuthRequired() {
        BossCliService bossCli = mock(BossCliService.class);
        AuthStateRepository repository = mock(AuthStateRepository.class);
        Map<String, Object> qrStartData = new LinkedHashMap<String, Object>();
        qrStartData.put("session_id", "qr1");
        qrStartData.put("status", "qr_ready");
        when(bossCli.hasLocalCredential()).thenReturn(false);
        when(bossCli.status()).thenReturn(status("auth_required", false));
        when(bossCli.qrStart()).thenReturn(envelope(qrStartData));
        Map<String, Object> qrStatusData = new LinkedHashMap<String, Object>();
        qrStatusData.put("status", "logged_in");
        when(bossCli.qrStatus("qr1")).thenReturn(envelope(qrStatusData));
        when(bossCli.readCredentialJson()).thenReturn("{\"cookies\":{\"a\":\"b\"}}");

        BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);
        Map<String, Object> start = service.startQrLogin("s1");
        Map<String, Object> done = service.loginStatus("s1", String.valueOf(start.get("qrSessionId")));

        assertEquals("qr1", start.get("qrSessionId"));
        assertTrue(Boolean.TRUE.equals(done.get("ok")));
        verify(repository).save(eq("jackwener/boss-cli"), eq("logged_in"), eq("{\"cookies\":{\"a\":\"b\"}}"), any(Map.class));
    }

    private Map<String, Object> savedState(String status, String credentialJson) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("status", status);
        row.put("credentialJson", credentialJson);
        return row;
    }

    private Map<String, Object> status(String status, boolean ok) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("ok", ok);
        data.put("authenticated", ok);
        data.put("status", status);
        return data;
    }

    private Map<String, Object> envelope(Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("ok", true);
        envelope.put("data", data);
        return envelope;
    }
}
