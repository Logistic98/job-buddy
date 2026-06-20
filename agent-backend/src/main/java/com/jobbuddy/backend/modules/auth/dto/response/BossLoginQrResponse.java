package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class BossLoginQrResponse extends MapBackedDto {
    public BossLoginQrResponse() {
    }

    public BossLoginQrResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static BossLoginQrResponse from(Map<String, Object> fields) {
        return new BossLoginQrResponse(fields);
    }
}
