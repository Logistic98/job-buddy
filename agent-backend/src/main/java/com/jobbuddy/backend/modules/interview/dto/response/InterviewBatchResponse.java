package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewBatchResponse extends MapBackedDto {
    public InterviewBatchResponse() {
    }

    public InterviewBatchResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewBatchResponse from(Map<String, Object> fields) {
        return new InterviewBatchResponse(fields);
    }
}
