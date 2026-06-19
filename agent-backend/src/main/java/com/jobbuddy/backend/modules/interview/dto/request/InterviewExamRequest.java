package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewExamRequest extends MapBackedDto {
    public InterviewExamRequest() {
    }

    public InterviewExamRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewExamRequest from(Map<String, Object> fields) {
        return new InterviewExamRequest(fields);
    }
}
