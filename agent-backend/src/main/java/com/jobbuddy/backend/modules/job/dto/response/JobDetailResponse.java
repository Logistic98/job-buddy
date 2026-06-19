package com.jobbuddy.backend.modules.job.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

/**
 * 岗位详情响应。Boss 直聘返回的职位描述字段是动态的，沿用 {@link MapBackedDto} 边界封装，
 * 在保持线上 JSON 结构不变的前提下，为 Controller 层提供一个具名返回类型，避免裸 Map 跨层传递。
 */
@Data
public class JobDetailResponse extends MapBackedDto {
    public JobDetailResponse() {
    }

    public JobDetailResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static JobDetailResponse from(Map<String, Object> fields) {
        return new JobDetailResponse(fields);
    }
}
