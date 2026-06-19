package com.jobbuddy.backend.common.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

@Data
public class NamedValueResponse extends MapBackedDto {
    public NamedValueResponse() {
    }

    public NamedValueResponse(String name, Object value) {
        put(name, value);
    }
}
