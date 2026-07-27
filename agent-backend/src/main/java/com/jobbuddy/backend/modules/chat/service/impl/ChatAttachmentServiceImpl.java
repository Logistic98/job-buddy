package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.dto.response.ChatAttachmentResponse;
import com.jobbuddy.backend.modules.chat.entity.ChatAttachment;
import com.jobbuddy.backend.modules.chat.repository.ChatAttachmentRepository;
import com.jobbuddy.backend.modules.chat.repository.ChatSessionRepository;
import com.jobbuddy.backend.modules.chat.service.ChatAttachmentService;
import com.jobbuddy.backend.modules.chat.service.ChatAttachmentTurnResult;
import com.jobbuddy.backend.modules.chat.storage.ChatAttachmentStorage;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import com.jobbuddy.backend.modules.interview.service.InterviewDocumentTextExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 实现聊天附件的安全解析、对象存储和消息轮次绑定。
 */
@Service
public class ChatAttachmentServiceImpl implements ChatAttachmentService {
  static final int MAX_ATTACHMENTS_PER_TURN = 5;
  static final long MAX_FILE_SIZE_BYTES = 128L * 1024L * 1024L;
  private static final Set<String> ALLOWED_SUFFIXES = Set.of("pdf", "doc", "docx", "txt", "md");

  private final ChatAttachmentRepository repository;
  private final ChatAttachmentStorage storage;
  private final InterviewDocumentTextExtractor textExtractor;
  private final ChatSessionRepository sessionRepository;

  public ChatAttachmentServiceImpl(
      ChatAttachmentRepository repository,
      ChatAttachmentStorage storage,
      InterviewDocumentTextExtractor textExtractor,
      ChatSessionRepository sessionRepository) {
    this.repository = repository;
    this.storage = storage;
    this.textExtractor = textExtractor;
    this.sessionRepository = sessionRepository;
  }

  @Override
  public ChatAttachmentResponse upload(MultipartFile file, String tenantId, String userId)
      throws IOException {
    String requestedSuffix = suffix(file == null ? null : file.getOriginalFilename());
    if (!ALLOWED_SUFFIXES.contains(requestedSuffix)) {
      throw new IllegalArgumentException("仅支持 PDF、DOC、DOCX、TXT、MD 文件");
    }
    if (file == null || file.isEmpty() || file.getSize() <= 0) {
      throw new IllegalArgumentException("上传文件不能为空");
    }
    if (file.getSize() > MAX_FILE_SIZE_BYTES) {
      throw new IllegalArgumentException("文件大小不能超过 128MB");
    }
    InterviewDocumentExtractResponse extracted = textExtractor.extract(file, MAX_FILE_SIZE_BYTES);
    String attachmentId = "att_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String suffix = suffix(extracted.getFileName());
    String contentType =
        extracted.getContentType() == null || extracted.getContentType().isBlank()
            ? "application/octet-stream"
            : extracted.getContentType();
    String objectName = "chat-attachments/" + userId + "/" + attachmentId + "." + suffix;
    String digest = sha256(file);
    storage.upload(file, objectName, contentType);

    ChatAttachment attachment = new ChatAttachment();
    attachment.setAttachmentId(attachmentId);
    attachment.setTenantId(tenantId);
    attachment.setUserId(userId);
    attachment.setFileName(extracted.getFileName());
    attachment.setContentType(contentType);
    attachment.setSuffix(suffix);
    attachment.setStoragePath(objectName);
    attachment.setSizeBytes(file.getSize());
    attachment.setSha256(digest);
    attachment.setParseStatus("ready");
    attachment.setExtractedText(extracted.getText());
    attachment.setCharacterCount(extracted.getCharacterCount());
    attachment.setTruncated(Boolean.TRUE.equals(extracted.getTruncated()));
    attachment.setCreatedAt(Instant.now());
    try {
      repository.save(attachment);
    } catch (RuntimeException error) {
      try {
        storage.delete(objectName);
      } catch (RuntimeException cleanupError) {
        error.addSuppressed(cleanupError);
      }
      throw error;
    }
    return response(attachment);
  }

  @Override
  @Transactional
  public List<Map<String, Object>> bindForTurn(
      List<String> attachmentIds, String tenantId, String userId, String sessionId, String turnId) {
    List<String> ids = normalizeIds(attachmentIds);
    if (ids.isEmpty()) return List.of();
    if (turnId == null || turnId.isBlank()) {
      throw new IllegalArgumentException("携带附件的消息必须提供 turnId");
    }
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (String attachmentId : ids) {
      ChatAttachment attachment = repository.find(tenantId, userId, attachmentId);
      if (attachment == null) throw new IllegalArgumentException("附件不存在或无权访问");
      if (!"ready".equals(attachment.getParseStatus())) {
        throw new IllegalArgumentException("附件尚未解析完成: " + attachment.getFileName());
      }
      if (!repository.bind(tenantId, userId, attachmentId, sessionId, turnId)) {
        throw new IllegalArgumentException("附件已被其他消息使用: " + attachment.getFileName());
      }
      attachment.setSessionId(sessionId);
      attachment.setTurnId(turnId);
      result.add(runtimeContext(attachment));
    }
    return result;
  }

  @Override
  @Transactional
  public ChatAttachmentTurnResult bindAndAppendUserMessage(
      List<String> attachmentIds,
      String tenantId,
      String userId,
      String sessionId,
      String turnId,
      String content) {
    List<Map<String, Object>> attachments =
        bindForTurn(attachmentIds, tenantId, userId, sessionId, turnId);
    List<Map<String, Object>> publicAttachments = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> attachment : attachments) {
      Map<String, Object> value = new LinkedHashMap<String, Object>(attachment);
      value.remove("content");
      value.remove("untrusted");
      publicAttachments.add(value);
    }
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("attachments", publicAttachments);
    boolean accepted =
        sessionRepository.appendUserMessageOnce(
            tenantId, userId, sessionId, turnId, content, metadata);
    return new ChatAttachmentTurnResult(accepted, attachments);
  }

  @Override
  public void deleteUnbound(String attachmentId, String tenantId, String userId) {
    ChatAttachment attachment = repository.find(tenantId, userId, attachmentId);
    if (attachment == null) throw new IllegalArgumentException("附件不存在或无权访问");
    if (!repository.deleteUnbound(tenantId, userId, attachmentId)) {
      throw new IllegalArgumentException("已发送的附件不能单独删除");
    }
    storage.delete(attachment.getStoragePath());
  }

  @Override
  @Transactional
  public void deleteSession(String tenantId, String userId, String sessionId) {
    List<ChatAttachment> attachments = repository.listBySession(tenantId, userId, sessionId);
    repository.deleteBySession(tenantId, userId, sessionId);
    for (ChatAttachment attachment : attachments) {
      storage.delete(attachment.getStoragePath());
    }
  }

  private List<String> normalizeIds(List<String> attachmentIds) {
    if (attachmentIds == null || attachmentIds.isEmpty()) return List.of();
    LinkedHashSet<String> ids = new LinkedHashSet<String>();
    for (String value : attachmentIds) {
      String id = value == null ? "" : value.trim();
      if (!id.isEmpty()) ids.add(id);
    }
    if (ids.size() > MAX_ATTACHMENTS_PER_TURN) {
      throw new IllegalArgumentException("每条消息最多上传 5 个附件");
    }
    return new ArrayList<String>(ids);
  }

  private Map<String, Object> runtimeContext(ChatAttachment attachment) {
    Map<String, Object> value = publicMetadata(attachment);
    value.put("content", attachment.getExtractedText());
    value.put("untrusted", true);
    return value;
  }

  static Map<String, Object> publicMetadata(ChatAttachment attachment) {
    Map<String, Object> value = new LinkedHashMap<String, Object>();
    value.put("attachmentId", attachment.getAttachmentId());
    value.put("fileName", attachment.getFileName());
    value.put("contentType", attachment.getContentType());
    value.put("suffix", attachment.getSuffix());
    value.put("sizeBytes", attachment.getSizeBytes());
    value.put("parseStatus", attachment.getParseStatus());
    value.put("characterCount", attachment.getCharacterCount());
    value.put("truncated", attachment.getTruncated());
    return value;
  }

  private ChatAttachmentResponse response(ChatAttachment attachment) {
    return new ChatAttachmentResponse(
        attachment.getAttachmentId(),
        attachment.getFileName(),
        attachment.getContentType(),
        attachment.getSuffix(),
        attachment.getSizeBytes(),
        attachment.getParseStatus(),
        attachment.getCharacterCount(),
        attachment.getTruncated());
  }

  private String suffix(String fileName) {
    int separator = fileName == null ? -1 : fileName.lastIndexOf('.');
    return separator < 0 ? "" : fileName.substring(separator + 1).trim().toLowerCase(Locale.ROOT);
  }

  private String sha256(MultipartFile file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = file.getInputStream()) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用", error);
    }
  }
}
