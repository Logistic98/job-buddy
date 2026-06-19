package com.jobbuddy.backend.modules.resume.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ResumeProfileResponse extends MapBackedDto {
    public ResumeProfileResponse() {
    }

    public ResumeProfileResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ResumeProfileResponse from(Map<String, Object> fields) {
        return new ResumeProfileResponse(fields);
    }
}
