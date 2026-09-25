package com.jobbuddy.backend.modules.auth.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.mapper.AuthStateMapper;
import com.jobbuddy.backend.modules.auth.security.BossCredentialCipher;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AuthQrSessionRepositoryTest {
  private final AuthStateMapper mapper = mock(AuthStateMapper.class);
  private final AuthStateRepository repository =
      new AuthStateRepository(mapper, new JsonCodec(), mock(BossCredentialCipher.class));

  @Test
  void qrSessionRequiresFutureExpirationAndPersistsOwnerBinding() {
    Instant expires = Instant.now().plusSeconds(240);
    repository.saveQrSession(" tenant ", " owner ", " chat ", " qr ", " token ", expires);
    ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
    var order = inOrder(mapper);
    order.verify(mapper).deleteExpiredQrSessions(any());
    order.verify(mapper).upsertQrSession(captured.capture());
    var row = captured.getValue();
    assertEquals("tenant", row.get("tenantId"));
    assertEquals("owner", row.get("userId"));
    assertEquals("chat", row.get("chatSessionId"));
    assertEquals("qr", row.get("qrSessionId"));
    assertEquals("token", row.get("toolSessionToken"));
    assertEquals(1, row.get("toolSessionVersion"));
    assertEquals(expires, row.get("expiresAt"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void blankOwnerSessionOrTokenIsRejectedBeforePersistence(String blank) {
    Instant expires = Instant.now().plusSeconds(240);
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveQrSession(blank, "owner", null, "qr", "token", expires));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveQrSession("tenant", blank, null, "qr", "token", expires));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveQrSession("tenant", "owner", null, blank, "token", expires));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveQrSession("tenant", "owner", null, "qr", blank, expires));
    verifyNoInteractions(mapper);
  }

  @Test
  void expiredSessionIsNeverSaved() {
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveQrSession("tenant", "owner", null, "qr", "token", Instant.EPOCH));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveQrSession("tenant", "owner", null, "qr", "token", null));
    verifyNoInteractions(mapper);
  }

  @Test
  void tokenUpdateUsesVersionCompareAndRejectsLostRace() {
    when(mapper.updateQrSessionToken(
            eq("qr"), eq("tenant"), eq("owner"), eq("new"), eq(2), eq(3), any()))
        .thenReturn(1, 0);
    repository.updateQrSessionToken(" tenant ", " owner ", " qr ", " new ", 2);
    assertThrows(
        IllegalStateException.class,
        () -> repository.updateQrSessionToken("tenant", "owner", "qr", "new", 2));
    repository.updateQrSessionToken("tenant", "owner", "qr", " ", 2);
    verify(mapper, times(2))
        .updateQrSessionToken(eq("qr"), eq("tenant"), eq("owner"), eq("new"), eq(2), eq(3), any());
  }

  @Test
  void lookupsAndDeletionTrimOwnerAndHandleEmptySession() {
    Map<String, Object> row = Map.of("qrSessionId", "qr");
    when(mapper.findQrSession("qr")).thenReturn(row);
    when(mapper.findActiveQrSession(eq("tenant"), eq("owner"), any())).thenReturn(row);
    when(mapper.findQrSessionByChat(eq("tenant"), eq("owner"), eq("chat"), any())).thenReturn(row);
    when(mapper.deleteQrSession("qr", "tenant", "owner")).thenReturn(1);
    when(mapper.deleteQrSessionsForOwner("tenant", "owner")).thenReturn(2);
    assertNull(repository.findQrSession(" "));
    assertNull(repository.findQrSessionByChat("tenant", "owner", " "));
    assertSame(row, repository.findQrSession(" qr "));
    assertSame(row, repository.findActiveQrSession(" tenant ", " owner "));
    assertSame(row, repository.findQrSessionByChat(" tenant ", " owner ", " chat "));
    assertFalse(repository.deleteQrSession("tenant", "owner", ""));
    assertTrue(repository.deleteQrSession(" tenant ", " owner ", " qr "));
    assertEquals(2, repository.deleteQrSessionsForOwner(" tenant ", " owner "));
  }

  @Test
  void logoutClearsOnlyOwnedProviderAndDefaultsStatus() {
    when(mapper.clearCredential(
            eq("tenant"), eq("owner"), eq("boss"), eq("logged_out"), anyString(), any()))
        .thenReturn(1);
    assertTrue(repository.clearCredential(" tenant ", " owner ", " boss ", null, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.clearCredential("tenant", "owner", " ", null, Map.of()));
  }
}
