package com.jobbuddy.backend.modules.interview.service;

import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import org.springframework.web.multipart.MultipartFile;

public interface InterviewDocumentTextExtractor {
  InterviewDocumentExtractResponse extract(MultipartFile file);
}
