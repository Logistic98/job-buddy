package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.chat.entity.ChatAttachment;
import com.jobbuddy.backend.modules.chat.repository.ChatAttachmentRepository;
import com.jobbuddy.backend.modules.chat.repository.ChatSessionRepository;
import com.jobbuddy.backend.modules.chat.service.ChatAttachmentTurnResult;
import com.jobbuddy.backend.modules.chat.storage.ChatAttachmentStorage;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import com.jobbuddy.backend.modules.interview.service.InterviewDocumentTextExtractor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 验证聊天附件上传、属主隔离、数量上限和消息绑定。
 */
class ChatAttachmentServiceImplTest {

  /**
   * 验证受支持文档会写入对象存储和附件记录。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldUploadAndPersistSupportedDocument() throws Exception {
    Fixture fixture = fixture();
    byte[] content = "# 项目说明".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file = new MockMultipartFile("file", "项目说明.md", "text/markdown", content);
    when(fixture.extractor.extract(file, ChatAttachmentServiceImpl.MAX_FILE_SIZE_BYTES))
        .thenReturn(
            new InterviewDocumentExtractResponse("项目说明.md", "text/markdown", "# 项目说明", 6, false));

    var response = fixture.service.upload(file, "tenant-a", "user-a");

    assertEquals("项目说明.md", response.getFileName());
    assertEquals("ready", response.getParseStatus());
    assertTrue(response.getAttachmentId().startsWith("att_"));
    verify(fixture.storage).upload(eq(file), any(String.class), eq("text/markdown"));
    verify(fixture.repository).save(any(ChatAttachment.class));
  }

  /**
   * 验证聊天附件只接受正式声明的五种格式。
   */
  @Test
  void shouldRejectUnsupportedFormatBeforeParsing() {
    Fixture fixture = fixture();
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "data.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8));

    assertEquals(
        "仅支持 PDF、DOC、DOCX、TXT、MD 文件",
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.upload(file, "tenant-a", "user-a"))
            .getMessage());
    verify(fixture.extractor, never()).extract(any(), anyLong());
  }

  /**
   * 验证聊天附件入口会把声明的五种格式全部交给共享文档解析器。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldAcceptEveryDeclaredDocumentFormat() throws Exception {
    Fixture fixture = fixture();
    Map<String, String> contentTypes =
        Map.of(
            "pdf", "application/pdf",
            "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain",
            "md", "text/markdown");

    for (Map.Entry<String, String> format : contentTypes.entrySet()) {
      String fileName = "reference." + format.getKey();
      MockMultipartFile file =
          new MockMultipartFile(
              "file", fileName, format.getValue(), "document".getBytes(StandardCharsets.UTF_8));
      when(fixture.extractor.extract(file, ChatAttachmentServiceImpl.MAX_FILE_SIZE_BYTES))
          .thenReturn(
              new InterviewDocumentExtractResponse(
                  fileName, format.getValue(), "document", 8, false));

      assertEquals(format.getKey(), fixture.service.upload(file, "tenant-a", "user-a").getSuffix());
    }
  }

  /**
   * 验证聊天附件入口拒绝超过 128MB 的文件，并允许恰好位于边界的文件进入解析。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldEnforceChatSpecificFileSizeLimit() throws Exception {
    Fixture fixture = fixture();
    MultipartFile oversized = mock(MultipartFile.class);
    when(oversized.getOriginalFilename()).thenReturn("large.pdf");
    when(oversized.isEmpty()).thenReturn(false);
    when(oversized.getSize()).thenReturn(ChatAttachmentServiceImpl.MAX_FILE_SIZE_BYTES + 1);

    assertEquals(
        "文件大小不能超过 128MB",
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.upload(oversized, "tenant-a", "user-a"))
            .getMessage());
    verify(fixture.extractor, never()).extract(any(), anyLong());

    MultipartFile boundary = mock(MultipartFile.class);
    when(boundary.getOriginalFilename()).thenReturn("large.txt");
    when(boundary.getContentType()).thenReturn("text/plain");
    when(boundary.isEmpty()).thenReturn(false);
    when(boundary.getSize()).thenReturn(ChatAttachmentServiceImpl.MAX_FILE_SIZE_BYTES);
    when(boundary.getInputStream())
        .thenReturn(new ByteArrayInputStream("boundary".getBytes(StandardCharsets.UTF_8)));
    when(fixture.extractor.extract(boundary, ChatAttachmentServiceImpl.MAX_FILE_SIZE_BYTES))
        .thenReturn(
            new InterviewDocumentExtractResponse("large.txt", "text/plain", "boundary", 8, false));

    assertEquals("large.txt", fixture.service.upload(boundary, "tenant-a", "user-a").getFileName());
  }

  /**
   * 验证附件绑定、公开元数据落库和正文仅进入运行时上下文。
   */
  @Test
  void shouldBindMultipleAttachmentsAndAppendUserMessageAtomically() {
    Fixture fixture = fixture();
    ChatAttachment first = attachment("att-1", "a.pdf", "第一份正文");
    ChatAttachment second = attachment("att-2", "b.docx", "第二份正文");
    when(fixture.repository.find("tenant-a", "user-a", "att-1")).thenReturn(first);
    when(fixture.repository.find("tenant-a", "user-a", "att-2")).thenReturn(second);
    when(fixture.repository.bind(any(), any(), any(), any(), any())).thenReturn(true);
    when(fixture.sessionRepository.appendUserMessageOnce(
            eq("tenant-a"), eq("user-a"), eq("session-a"), eq("turn-a"), eq("对比两份文件"), any()))
        .thenReturn(true);

    ChatAttachmentTurnResult result =
        fixture.service.bindAndAppendUserMessage(
            List.of("att-1", "att-2"), "tenant-a", "user-a", "session-a", "turn-a", "对比两份文件");

    assertTrue(result.isAccepted());
    assertEquals(2, result.getAttachments().size());
    assertEquals("第一份正文", result.getAttachments().get(0).get("content"));
    ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
    verify(fixture.sessionRepository)
        .appendUserMessageOnce(
            eq("tenant-a"),
            eq("user-a"),
            eq("session-a"),
            eq("turn-a"),
            eq("对比两份文件"),
            metadata.capture());
    List<?> persisted = (List<?>) metadata.getValue().get("attachments");
    assertEquals(2, persisted.size());
    assertTrue(((Map<?, ?>) persisted.get(0)).get("content") == null);
  }

  /**
   * 验证数量上限和跨属主访问均失败关闭。
   */
  @Test
  void shouldRejectTooManyOrForeignAttachments() {
    Fixture fixture = fixture();
    assertEquals(
        "每条消息最多上传 5 个附件",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    fixture.service.bindForTurn(
                        List.of("1", "2", "3", "4", "5", "6"),
                        "tenant-a",
                        "user-a",
                        "session-a",
                        "turn-a"))
            .getMessage());

    when(fixture.repository.find("tenant-a", "user-a", "foreign")).thenReturn(null);
    assertEquals(
        "附件不存在或无权访问",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    fixture.service.bindForTurn(
                        List.of("foreign"), "tenant-a", "user-a", "session-a", "turn-a"))
            .getMessage());
  }

  private Fixture fixture() {
    ChatAttachmentRepository repository = mock(ChatAttachmentRepository.class);
    ChatAttachmentStorage storage = mock(ChatAttachmentStorage.class);
    InterviewDocumentTextExtractor extractor = mock(InterviewDocumentTextExtractor.class);
    ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
    return new Fixture(
        repository,
        storage,
        extractor,
        sessionRepository,
        new ChatAttachmentServiceImpl(repository, storage, extractor, sessionRepository));
  }

  private ChatAttachment attachment(String id, String fileName, String text) {
    ChatAttachment attachment = new ChatAttachment();
    attachment.setAttachmentId(id);
    attachment.setFileName(fileName);
    attachment.setContentType("application/octet-stream");
    attachment.setSuffix(fileName.substring(fileName.lastIndexOf('.') + 1));
    attachment.setSizeBytes(100L);
    attachment.setParseStatus("ready");
    attachment.setExtractedText(text);
    attachment.setCharacterCount(text.length());
    attachment.setTruncated(false);
    return attachment;
  }

  private record Fixture(
      ChatAttachmentRepository repository,
      ChatAttachmentStorage storage,
      InterviewDocumentTextExtractor extractor,
      ChatSessionRepository sessionRepository,
      ChatAttachmentServiceImpl service) {}
}
