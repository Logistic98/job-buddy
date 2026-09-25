package com.jobbuddy.backend.modules.prompt.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class FilePromptRegistryServiceTest {
  @TempDir Path directory;

  @Test
  void externalProfileOverridesDefaultAndMissingProfileFallsBack() throws Exception {
    Files.createDirectories(directory.resolve("frontend"));
    Files.writeString(
        directory.resolve("frontend/workbench.yaml"),
        "profiles:\n  default:\n    title: fallback\n  custom:\n    title: custom-title\n");
    FilePromptRegistryService service = new FilePromptRegistryService();
    ReflectionTestUtils.setField(service, "rootDir", directory.toFile());
    assertEquals("custom-title", service.frontendWorkbench(" custom ").path("title").asText());
    assertEquals("fallback", service.frontendWorkbench("missing").path("title").asText());
    assertEquals("missing", service.frontendWorkbench("missing").path("profile").asText());
  }

  @Test
  void malformedOrNonMappingConfigurationUsesSafeFallback() throws Exception {
    Files.createDirectories(directory.resolve("frontend"));
    Path config = directory.resolve("frontend/workbench.yaml");
    Files.writeString(config, "- not-a-profile\n");
    FilePromptRegistryService service = new FilePromptRegistryService();
    ReflectionTestUtils.setField(service, "rootDir", directory.toFile());
    assertEquals("智能工作台", service.frontendWorkbench("default").path("title").asText());
    assertTrue(service.frontendWorkbench("default").path("quick_prompts").isArray());
    Files.writeString(config, "broken: [\n");
    assertFalse(service.frontendWorkbench("default").path("title").asText().isBlank());
  }

  @Test
  void profileConfigReadsExternalYamlAndFallsBackForMissingProfile() throws Exception {
    Files.createDirectories(directory.resolve("profiles"));
    Files.writeString(directory.resolve("profiles/default.yaml"), "name: fallback\n");
    Files.writeString(directory.resolve("profiles/custom.yaml"), "name: custom\n");
    FilePromptRegistryService service = new FilePromptRegistryService();
    ReflectionTestUtils.setField(service, "rootDir", directory.toFile());
    assertEquals("custom", service.profileConfig("custom").path("name").asText());
    assertEquals("fallback", service.profileConfig("unknown-test-profile").path("name").asText());
  }
}
