package com.jobbuddy.backend.modules.interview.service;

import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 定义面试文档文本提取器。
 */
public interface InterviewDocumentTextExtractor {
  /**
   * 提取文档文本。
   *
   * @param file 上传文件
   * @return 提取结果
   */
  InterviewDocumentExtractResponse extract(MultipartFile file);
}
