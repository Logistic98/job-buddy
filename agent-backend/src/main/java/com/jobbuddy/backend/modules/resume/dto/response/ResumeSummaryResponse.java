package com.jobbuddy.backend.modules.resume.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ResumeSummaryResponse extends MapBackedDto {
    public ResumeSummaryResponse() {
    }

    public ResumeSummaryResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ResumeSummaryResponse from(Map<String, Object> fields) {
        return new ResumeSummaryResponse(fields);
    }
}
