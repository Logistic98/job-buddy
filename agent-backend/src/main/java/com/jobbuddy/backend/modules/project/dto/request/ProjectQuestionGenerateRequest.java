package com.jobbuddy.backend.modules.project.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ProjectQuestionGenerateRequest extends MapBackedDto {
    public ProjectQuestionGenerateRequest() {
    }

    public ProjectQuestionGenerateRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static ProjectQuestionGenerateRequest from(Map<String, Object> fields) {
        return new ProjectQuestionGenerateRequest(fields);
    }
}
