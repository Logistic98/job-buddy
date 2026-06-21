package com.jobbuddy.backend.modules.system.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class SystemMemoryResponse extends MapBackedDto {
    public SystemMemoryResponse() {
    }

    public SystemMemoryResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static SystemMemoryResponse from(Map<String, Object> fields) {
        return new SystemMemoryResponse(fields);
    }
}
