package com.jobbuddy.backend.modules.system.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.Data;

/**
 * 承载系统设置响应数据。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemSettingsResponse {
  private Workspace workspace;
  private Services services;
  private Memory memory;
  private Blacklist blacklist;
  private String updatedAt;
  private JsonNode runtime;
  private JsonNode serviceStatuses;
  private String settingsPath;

  /**
   * 定义工作区。
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Workspace {
    private Integer maxJobsPerRecommend;
    private Integer recommendOverfetchFactor;
    private Integer minimumRecommendedMatchScore;
    private Integer bossSearchMaxPages;
    private Integer bossSearchMaxPageDepth;
    private Integer bossSearchCacheTtlMinutes;
    private Integer bossSearchCooldownMinutesOnRisk;
    private Integer runtimeMaxTurns;
    private Integer runtimeMaxToolCalls;
    private Integer runtimeMaxFailures;
    private Integer maxResumeBytes;
    private Integer resumeWriterVersionLimit;
  }

  /**
   * 定义服务列表。
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Services {
    private String intentUrl;
    private String runtimeUrl;
    private String memoryUrl;
    private String toolUrl;
    private String evalUrl;
    private String sandboxUrl;
    private String connectTimeout;
    private String readTimeout;
  }

  /**
   * 定义记忆。
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Memory {
    private Boolean enabled;
    private Boolean autoSaveChat;
    private Boolean autoUseMemory;
    private Integer maxItems;
    private List<SystemMemoryResponse> items;
  }

  /**
   * 定义黑名单。
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Blacklist {
    private Boolean enabled;
    private String matchMode;
    private List<Item> items;
  }

  /**
   * 定义数据项。
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Item {
    private String name;
    private String type;
    private Boolean enabled;
    private String reason;
    private String createdAt;
  }
}
