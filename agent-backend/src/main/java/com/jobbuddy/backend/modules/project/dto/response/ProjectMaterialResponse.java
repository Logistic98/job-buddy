package com.jobbuddy.backend.modules.project.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ProjectMaterialResponse extends MapBackedDto {
    public ProjectMaterialResponse() {
    }

    public ProjectMaterialResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ProjectMaterialResponse from(Map<String, Object> fields) {
        return new ProjectMaterialResponse(fields);
    }
}
