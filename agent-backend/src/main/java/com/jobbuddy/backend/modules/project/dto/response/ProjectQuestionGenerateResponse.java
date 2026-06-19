package com.jobbuddy.backend.modules.project.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ProjectQuestionGenerateResponse extends MapBackedDto {
    public ProjectQuestionGenerateResponse() {
    }

    public ProjectQuestionGenerateResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ProjectQuestionGenerateResponse from(Map<String, Object> fields) {
        return new ProjectQuestionGenerateResponse(fields);
    }
}
