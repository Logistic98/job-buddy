package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewImportResponse extends MapBackedDto {
    public InterviewImportResponse() {
    }

    public InterviewImportResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewImportResponse from(Map<String, Object> fields) {
        return new InterviewImportResponse(fields);
    }
}
