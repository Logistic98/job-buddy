package com.jobbuddy.backend.modules.resume.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ResumeAssetUploadResponse extends MapBackedDto {
    public ResumeAssetUploadResponse() {
    }

    public ResumeAssetUploadResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ResumeAssetUploadResponse from(Map<String, Object> fields) {
        return new ResumeAssetUploadResponse(fields);
    }
}
