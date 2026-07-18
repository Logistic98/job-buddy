package com.jobbuddy.backend.modules.prompt.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface PromptRegistryService {
  String activeProfile();

  JsonNode frontendWorkbench(String profile);

  JsonNode profileConfig(String profile);
}
