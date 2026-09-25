package com.jobbuddy.backend.modules.resume.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ResumeAssetTokenSignerTest {
  private static final String KEY = "synthetic-signing-key";
  private final JsonCodec json = new JsonCodec();
  private final JobBuddyProperties properties = properties();
  private final ResumeAssetTokenSigner signer = new ResumeAssetTokenSigner(properties, json);

  @ParameterizedTest
  @ValueSource(strings = {"jpg", "jpeg", "PNG", "webp"})
  void validTokenBindsResourceToOwner(String suffix) {
    String path = "owner/assets/image." + suffix;
    String token = signer.signAssetToken(path, "owner");
    assertEquals(path, signer.requireAssetObjectName(token, "owner"));
    assertThrows(
        IllegalArgumentException.class, () -> signer.requireAssetObjectName(token, "other"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "a", "a.b.c", ".b", "a.", "a.%%%", "a.YQ"})
  void malformedAndForgedTokensAreRejected(String token) {
    assertThrows(
        IllegalArgumentException.class, () -> signer.requireAssetObjectName(token, "owner"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "other/assets/image.png",
        "owner/resumes/image.png",
        "owner/assets/file.exe",
        "owner/assets/file.",
        "owner/assets/file"
      })
  void evenValidSignatureCannotAuthorizeAnotherPathOrUnsafeType(String path) {
    assertThrows(
        IllegalArgumentException.class,
        () -> signer.requireAssetObjectName(signer.signAssetToken(path, "owner"), "owner"));
  }

  @Test
  void expiredOrInvalidExpirationIsRejected() throws Exception {
    for (Object expiration :
        new Object[] {Instant.now().minusSeconds(60).getEpochSecond(), "invalid", "1"}) {
      String token =
          token(
              json.toJson(
                  Map.of(
                      "objectName",
                      "owner/assets/image.png",
                      "userId",
                      "owner",
                      "exp",
                      expiration)));
      assertEquals(
          "资源链接已过期",
          assertThrows(
                  IllegalArgumentException.class,
                  () -> signer.requireAssetObjectName(token, "owner"))
              .getMessage());
    }
  }

  @Test
  void signedMalformedJsonIsRejected() throws Exception {
    String token = token("not-json");
    assertThrows(
        IllegalArgumentException.class, () -> signer.requireAssetObjectName(token, "owner"));
  }

  @Test
  void configuredKeySurvivesSignerRestartAndMinimumTtlIsApplied() {
    properties.getAuth().setAssetUrlTtlSeconds(1);
    String token = signer.signAssetToken("owner/assets/image.png", "owner");
    assertEquals(
        "owner/assets/image.png",
        new ResumeAssetTokenSigner(properties, json).requireAssetObjectName(token, "owner"));
    var payload =
        json.toMap(
            new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8));
    assertTrue(((Number) payload.get("exp")).longValue() >= Instant.now().getEpochSecond() + 58);
  }

  @Test
  void minioFallbackKeySurvivesRestart() {
    properties.getAuth().setAssetUrlSigningKey("");
    properties.getMinio().setSecretKey("synthetic-minio-secret");
    ResumeAssetTokenSigner first = new ResumeAssetTokenSigner(properties, json);
    ResumeAssetTokenSigner second = new ResumeAssetTokenSigner(properties, json);
    assertEquals(
        "owner/assets/image.png",
        second.requireAssetObjectName(
            first.signAssetToken("owner/assets/image.png", "owner"), "owner"));
  }

  private String token(String payload) throws Exception {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(KEY.getBytes(StandardCharsets.UTF_8)),
            "HmacSHA256"));
    return encoded
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
  }

  private static JobBuddyProperties properties() {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getAuth().setAssetUrlSigningKey(KEY);
    return properties;
  }
}
