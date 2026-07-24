package com.jobbuddy.backend.modules.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessageResponse {
  private Long id;
  private String turnId;
  private String role;
  private String content;
  private JsonNode metadata;
  private JsonNode jobCards;
  private JsonNode resumeMatch;
  private JsonNode toolEvents;
  private JsonNode reasoning;
  private Object createdAt;
}
