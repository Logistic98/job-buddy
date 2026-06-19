package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewImportRequest extends MapBackedDto {
    public InterviewImportRequest() {
    }

    public InterviewImportRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewImportRequest from(Map<String, Object> fields) {
        return new InterviewImportRequest(fields);
    }
}
