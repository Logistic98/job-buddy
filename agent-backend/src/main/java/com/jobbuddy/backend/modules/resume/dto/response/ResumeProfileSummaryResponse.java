package com.jobbuddy.backend.modules.resume.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ResumeProfileSummaryResponse extends MapBackedDto {
    public ResumeProfileSummaryResponse() {
    }

    public ResumeProfileSummaryResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ResumeProfileSummaryResponse from(Map<String, Object> fields) {
        return new ResumeProfileSummaryResponse(fields);
    }
}
