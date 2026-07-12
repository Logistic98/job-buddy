package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.mapper.AuthStateMapper;
import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import com.jobbuddy.backend.modules.auth.security.BossCredentialCipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthStateRepositoryEncryptionTest {

  @Test
  void saveMustWriteCiphertextInsteadOfCredentialJson() {
    AuthStateMapper mapper = mock(AuthStateMapper.class);
    when(mapper.countByProvider("tenant-a", "user-a", "jackwener/boss-cli")).thenReturn(0);
    AuthStateRepository repository = repository(mapper, propertiesWithKey());
    ArgumentCaptor<Map<String, Object>> state = mapCaptor();

    repository.save(
        "tenant-a",
        "user-a",
        "jackwener/boss-cli",
        "logged_in",
        "{\"cookies\":{\"wt2\":\"secret\"}}",
        Collections.<String, Object>emptyMap());

    verify(mapper).insertState(state.capture());
    String stored = String.valueOf(state.getValue().get("credentialJson"));
    assertTrue(stored.startsWith("enc:v1:"));
    assertTrue(!stored.contains("secret"));
  }

  @Test
  void plaintextCredentialMustBeRejected() {
    AuthStateMapper mapper = mock(AuthStateMapper.class);
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("credentialJson", "{\"cookies\":{}}");
    row.put("metadataJson", "{}");
    when(mapper.findByProvider("tenant-a", "user-a", "jackwener/boss-cli")).thenReturn(row);
    AuthStateRepository repository = repository(mapper, propertiesWithKey());

    assertThrows(
        IllegalStateException.class,
        () -> repository.findByProvider("tenant-a", "user-a", "jackwener/boss-cli"));
  }

  private AuthStateRepository repository(AuthStateMapper mapper, JobBuddyProperties properties) {
    return new AuthStateRepository(mapper, new JsonCodec(), new BossCredentialCipher(properties));
  }

  private JobBuddyProperties propertiesWithKey() {
    JobBuddyProperties properties = new JobBuddyProperties();
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    properties.getAuth().setBossCredentialEncryptionKey(Base64.getEncoder().encodeToString(key));
    return properties;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
  }
}
