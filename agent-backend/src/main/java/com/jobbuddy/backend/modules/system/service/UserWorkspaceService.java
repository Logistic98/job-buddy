package com.jobbuddy.backend.modules.system.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface UserWorkspaceService {
  JsonNode get(String userId, String stateKey);

  JsonNode save(String userId, String stateKey, JsonNode state);

  void delete(String userId, String stateKey);
}
