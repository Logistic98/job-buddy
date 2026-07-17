package com.jobbuddy.backend.modules.journey.dto.response;

import java.util.List;
import lombok.Data;

@Data
public class JourneyAnalysisResponse {
  private String summary;
  private Metrics metrics;
  private List<ScoreGroup> scoreGroups;
  private List<String> strengths;
  private List<String> risks;
  private List<String> nextActions;
  private List<String> preparationPlan;
  private String followUpMessage;
  private String generatedAt;

  @Data
  public static class Metrics {
    private Integer total;
    private Integer active;
    private Integer passed;
    private Integer failed;
    private Integer pending;
    private Integer offer;
    private Integer score;
    private String topRound;
    private String topDomain;
  }

  @Data
  public static class ScoreGroup {
    private String key;
    private String label;
    private Integer score;
    private String description;
  }
}
