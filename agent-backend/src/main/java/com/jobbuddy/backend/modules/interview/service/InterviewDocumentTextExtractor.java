package com.jobbuddy.backend.modules.interview.service;

import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 定义面试文档文本提取器。
 */
public interface InterviewDocumentTextExtractor {
  long DEFAULT_MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;

  /**
   * 提取文档文本。
   *
   * @param file 上传文件
   * @return 提取结果
   */
  default InterviewDocumentExtractResponse extract(MultipartFile file) {
    return extract(file, DEFAULT_MAX_FILE_SIZE_BYTES);
  }

  /**
   * 按调用入口声明的大小上限提取文档文本。
   *
   * @param file 上传文件
   * @param maxFileSizeBytes 当前入口允许的最大文件字节数
   * @return 提取结果
   */
  InterviewDocumentExtractResponse extract(MultipartFile file, long maxFileSizeBytes);
}
