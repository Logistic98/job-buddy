package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewGenerateResponse extends MapBackedDto {
    public InterviewGenerateResponse() {
    }

    public InterviewGenerateResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewGenerateResponse from(Map<String, Object> fields) {
        return new InterviewGenerateResponse(fields);
    }
}
