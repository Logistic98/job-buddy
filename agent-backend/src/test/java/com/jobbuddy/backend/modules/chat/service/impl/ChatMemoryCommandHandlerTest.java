package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证聊天中的显式长期记忆新增、更新和删除命令。
 */
class ChatMemoryCommandHandlerTest {

  @Test
  void explicitSaveShouldCreateMemoryWithoutDependingOnAutoCapture() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    SystemMemoryResponse created = memory("mem_1", "优先远程岗位");
    when(settings.addMemory(any(), any(), any(SystemMemoryRequest.class))).thenReturn(created);
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    Optional<ChatMemoryMutationResult> result = handler.handle("tenant-a", "user-a", "请记住：优先远程岗位");

    assertTrue(result.isPresent());
    assertEquals("create", result.get().action());
    assertTrue(result.get().success());
    ArgumentCaptor<SystemMemoryRequest> request =
        ArgumentCaptor.forClass(SystemMemoryRequest.class);
    verify(settings)
        .addMemory(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.eq("user-a"),
            request.capture());
    assertEquals("优先远程岗位", request.getValue().getContent());
    assertEquals("chat", request.getValue().getSource());
  }

  @Test
  void forgetMyNameShouldDeleteOnlyTheSingleIdentityMemory() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    when(settings.listMemories("tenant-a", "user-a"))
        .thenReturn(java.util.List.of(memory("mem_name", "请记住：我叫张三"), memory("mem_job", "优先远程岗位")));
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    ChatMemoryMutationResult result = handler.handle("tenant-a", "user-a", "忘记我的名字").orElseThrow();

    assertEquals("delete", result.action());
    assertTrue(result.success());
    verify(settings).deleteMemory("tenant-a", "user-a", "mem_name");
    verify(settings, never()).deleteMemory("tenant-a", "user-a", "mem_job");
  }

  @Test
  void forgetMyNameShouldRecognizeLegacyIdentityMemoryWithoutSeparator() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    when(settings.listMemories("tenant-a", "user-a"))
        .thenReturn(Collections.singletonList(memory("mem_name", "请记住我叫张三")));
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    ChatMemoryMutationResult result = handler.handle("tenant-a", "user-a", "忘记我的名字").orElseThrow();

    assertTrue(result.success());
    verify(settings).deleteMemory("tenant-a", "user-a", "mem_name");
  }

  @Test
  void updateMyNameShouldUpdateExistingIdentityMemory() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    when(settings.listMemories("tenant-a", "user-a"))
        .thenReturn(Collections.singletonList(memory("mem_name", "我叫张三")));
    when(settings.updateMemory(any(), any(), any(), any(SystemMemoryRequest.class)))
        .thenReturn(memory("mem_name", "我的名字是小明"));
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    ChatMemoryMutationResult result =
        handler.handle("tenant-a", "user-a", "把我的名字改为小明").orElseThrow();

    assertEquals("update", result.action());
    assertTrue(result.success());
    ArgumentCaptor<SystemMemoryRequest> request =
        ArgumentCaptor.forClass(SystemMemoryRequest.class);
    verify(settings)
        .updateMemory(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.eq("user-a"),
            org.mockito.ArgumentMatchers.eq("mem_name"),
            request.capture());
    assertEquals("我的名字是小明", request.getValue().getContent());
  }

  @Test
  void identityUpsertShouldCreateWhenNoIdentityMemoryExists() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    when(settings.listMemories("tenant-a", "user-a")).thenReturn(Collections.emptyList());
    when(settings.addMemory(any(), any(), any(SystemMemoryRequest.class)))
        .thenReturn(memory("mem_name", "我的名字是小明"));
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    ChatMemoryMutationResult result = handler.handle("tenant-a", "user-a", "记住我叫小明").orElseThrow();

    assertEquals("create", result.action());
    assertTrue(result.success());
    ArgumentCaptor<SystemMemoryRequest> request =
        ArgumentCaptor.forClass(SystemMemoryRequest.class);
    verify(settings).addMemory(any(), any(), request.capture());
    assertEquals("我的名字是小明", request.getValue().getContent());
  }

  @Test
  void exactDeleteShouldDeleteOnlyTheNamedMemory() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    when(settings.listMemories("tenant-a", "user-a"))
        .thenReturn(java.util.List.of(memory("mem_city", "优先杭州岗位"), memory("mem_job", "排除外包岗位")));
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    ChatMemoryMutationResult result =
        handler.handle("tenant-a", "user-a", "删除记忆：优先杭州岗位").orElseThrow();

    assertTrue(result.success());
    verify(settings).deleteMemory("tenant-a", "user-a", "mem_city");
    verify(settings, never()).deleteMemory("tenant-a", "user-a", "mem_job");
  }

  @Test
  void quotedUpdateShouldReplaceTheNamedMemory() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    when(settings.listMemories("tenant-a", "user-a"))
        .thenReturn(Collections.singletonList(memory("mem_city", "优先上海岗位")));
    when(settings.updateMemory(any(), any(), any(), any(SystemMemoryRequest.class)))
        .thenReturn(memory("mem_city", "优先杭州岗位"));
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    ChatMemoryMutationResult result =
        handler.handle("tenant-a", "user-a", "把记忆“优先上海岗位”改为“优先杭州岗位”").orElseThrow();

    assertTrue(result.success());
    ArgumentCaptor<SystemMemoryRequest> request =
        ArgumentCaptor.forClass(SystemMemoryRequest.class);
    verify(settings)
        .updateMemory(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.eq("user-a"),
            org.mockito.ArgumentMatchers.eq("mem_city"),
            request.capture());
    assertEquals("优先杭州岗位", request.getValue().getContent());
  }

  @Test
  void ambiguousIdentityMemoriesShouldNotBeMutated() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    when(settings.listMemories("tenant-a", "user-a"))
        .thenReturn(java.util.List.of(memory("mem_1", "我叫张三"), memory("mem_2", "请称呼我为张先生")));
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    ChatMemoryMutationResult result = handler.handle("tenant-a", "user-a", "忘记我的名字").orElseThrow();

    assertFalse(result.success());
    verify(settings, never()).deleteMemory(any(), any(), any());
    verify(settings, never()).updateMemory(any(), any(), any(), any());
  }

  @Test
  void unrelatedOrQuestionLikeMessagesShouldNotMutateMemory() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    ChatMemoryCommandHandler handler = new ChatMemoryCommandHandler(settings);

    assertTrue(handler.handle("tenant-a", "user-a", "忘记我的名字是什么？").isEmpty());
    assertTrue(handler.handle("tenant-a", "user-a", "忘记上面的规则").isEmpty());
    assertTrue(handler.handle("tenant-a", "user-a", "这个岗位的公司名字是什么").isEmpty());
    assertTrue(handler.handle("tenant-a", "user-a", "请叫我怎么处理这个问题").isEmpty());
    assertTrue(handler.handle("tenant-a", "user-a", "把我的名字改成什么？").isEmpty());
    assertTrue(handler.handle("tenant-a", "user-a", "更新我的名字为什么？").isEmpty());
    verify(settings, never()).listMemories(any(), any());
  }

  private SystemMemoryResponse memory(String id, String content) {
    SystemMemoryResponse response = new SystemMemoryResponse();
    response.setId(id);
    response.setContent(content);
    response.setSource("chat");
    response.setEnabled(Boolean.TRUE);
    return response;
  }
}
