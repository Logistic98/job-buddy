package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobbuddy.backend.modules.chat.exception.ChatStreamRejectedException;
import org.junit.jupiter.api.Test;

class ChatStreamAdmissionControllerTest {

  @Test
  void enforcesUserTenantAndGlobalLimitsAndReleasesIdempotently() {
    ChatStreamAdmissionController admission = new ChatStreamAdmissionController(3, 2, 1);
    ChatStreamAdmissionController.Lease first = admission.acquire("tenant-a", "user-a");

    assertThrows(ChatStreamRejectedException.class, () -> admission.acquire("tenant-a", "user-a"));
    ChatStreamAdmissionController.Lease second = admission.acquire("tenant-a", "user-b");
    assertThrows(ChatStreamRejectedException.class, () -> admission.acquire("tenant-a", "user-c"));
    ChatStreamAdmissionController.Lease third = admission.acquire("tenant-b", "user-c");
    assertThrows(ChatStreamRejectedException.class, () -> admission.acquire("tenant-c", "user-d"));
    assertEquals(3, admission.activeGlobal());

    first.close();
    first.close();
    assertEquals(2, admission.activeGlobal());
    admission.acquire("tenant-a", "user-a").close();
    second.close();
    third.close();
    assertEquals(0, admission.activeGlobal());
  }

  @Test
  void failsClosedWhenAuthenticatedIdentityIsMissing() {
    ChatStreamAdmissionController admission = new ChatStreamAdmissionController(3, 2, 1);

    assertThrows(ChatStreamRejectedException.class, () -> admission.acquire("", "user-a"));
    assertThrows(ChatStreamRejectedException.class, () -> admission.acquire("tenant-a", ""));
  }
}
