package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewGenerateRequest extends MapBackedDto {
    public InterviewGenerateRequest() {
    }

    public InterviewGenerateRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewGenerateRequest from(Map<String, Object> fields) {
        return new InterviewGenerateRequest(fields);
    }
}
