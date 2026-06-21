package com.jobbuddy.backend.modules.system.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class SystemSettingsResponse extends MapBackedDto {
    public SystemSettingsResponse() {
    }

    public SystemSettingsResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static SystemSettingsResponse from(Map<String, Object> fields) {
        return new SystemSettingsResponse(fields);
    }
}
