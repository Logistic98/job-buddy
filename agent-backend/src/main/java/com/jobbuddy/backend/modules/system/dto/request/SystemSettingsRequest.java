package com.jobbuddy.backend.modules.system.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class SystemSettingsRequest extends MapBackedDto {
    public SystemSettingsRequest() {
    }

    public SystemSettingsRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static SystemSettingsRequest from(Map<String, Object> fields) {
        return new SystemSettingsRequest(fields);
    }
}
