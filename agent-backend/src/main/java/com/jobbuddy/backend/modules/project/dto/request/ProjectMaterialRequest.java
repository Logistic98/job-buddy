package com.jobbuddy.backend.modules.project.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ProjectMaterialRequest extends MapBackedDto {
    public ProjectMaterialRequest() {
    }

    public ProjectMaterialRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static ProjectMaterialRequest from(Map<String, Object> fields) {
        return new ProjectMaterialRequest(fields);
    }
}
