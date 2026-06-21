package com.jobbuddy.backend.modules.system.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class SystemMemoryRequest extends MapBackedDto {
    public SystemMemoryRequest() {
    }

    public SystemMemoryRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static SystemMemoryRequest from(Map<String, Object> fields) {
        return new SystemMemoryRequest(fields);
    }
}
