package com.jobbuddy.backend.modules.chat.controller;

import com.jobbuddy.backend.common.dto.response.DeletedResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.chat.dto.response.ChatAttachmentResponse;
import com.jobbuddy.backend.modules.chat.service.ChatAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供智能引擎消息附件上传与删除接口。
 */
@Tag(name = "聊天附件接口")
@RestController
@RequirePermission(PermissionCodes.CHAT_USE)
@RequestMapping("/api/chat/attachments")
public class ChatAttachmentController {
  private final ChatAttachmentService attachmentService;

  public ChatAttachmentController(ChatAttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @Operation(summary = "上传并解析聊天附件")
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<ChatAttachmentResponse> upload(
      @RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
    return ApiResponse.success(
        attachmentService.upload(
            file,
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request)));
  }

  @Operation(summary = "删除尚未发送的聊天附件")
  @DeleteMapping("/{attachmentId}")
  public ApiResponse<DeletedResponse> delete(
      @PathVariable String attachmentId, HttpServletRequest request) {
    attachmentService.deleteUnbound(
        attachmentId,
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request));
    return ApiResponse.success(new DeletedResponse(true));
  }
}
