package com.jobbuddy.backend.modules.project.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ProjectResponse extends MapBackedDto {
    public ProjectResponse() {
    }

    public ProjectResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ProjectResponse from(Map<String, Object> fields) {
        return new ProjectResponse(fields);
    }
}
