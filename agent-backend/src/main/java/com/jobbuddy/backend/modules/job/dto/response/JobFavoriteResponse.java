package com.jobbuddy.backend.modules.job.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JobFavoriteResponse extends MapBackedDto {
    public JobFavoriteResponse() {
    }

    public JobFavoriteResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static JobFavoriteResponse from(Map<String, Object> fields) {
        return new JobFavoriteResponse(fields);
    }
}
