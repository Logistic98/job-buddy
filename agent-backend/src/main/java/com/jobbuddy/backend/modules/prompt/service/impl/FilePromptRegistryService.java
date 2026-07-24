package com.jobbuddy.backend.modules.prompt.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.prompt.service.PromptRegistryService;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class FilePromptRegistryService implements PromptRegistryService {
  private static final Logger log = LoggerFactory.getLogger(FilePromptRegistryService.class);
  private static final String DEFAULT_PROFILE = "default";
  private final File rootDir;
  private final Yaml yaml = new Yaml();
  private final JsonCodec jsonCodec = new JsonCodec();

  public FilePromptRegistryService() {
    String configured = System.getenv("JOB_BUDDY_PROMPT_DIR");
    this.rootDir =
        new File(
            configured == null || configured.trim().isEmpty()
                ? "src/main/resources/prompts"
                : configured.trim());
  }

  @Override
  public String activeProfile() {
    String value = System.getenv("JOB_BUDDY_PROMPT_PROFILE");
    return value == null || value.trim().isEmpty() ? DEFAULT_PROFILE : value.trim();
  }

  @Override
  public JsonNode frontendWorkbench(String profile) {
    Map<String, Object> all = readYaml("frontend/workbench.yaml");
    Map<String, Object> profiles = asMap(all.get("profiles"));
    Map<String, Object> selected = asMap(profiles.get(normalizeProfile(profile)));
    if (selected.isEmpty()) selected = asMap(profiles.get(DEFAULT_PROFILE));
    if (selected.isEmpty()) selected = fallbackWorkbench();
    Map<String, Object> data = new LinkedHashMap<String, Object>(selected);
    data.put("profile", normalizeProfile(profile));
    return jsonCodec.toTree(data);
  }

  @Override
  public JsonNode profileConfig(String profile) {
    String normalized = normalizeProfile(profile);
    Map<String, Object> data = readYaml("profiles/" + normalized + ".yaml");
    if (data.isEmpty() && !DEFAULT_PROFILE.equals(normalized))
      data = readYaml("profiles/" + DEFAULT_PROFILE + ".yaml");
    return jsonCodec.toTree(data);
  }

  private String normalizeProfile(String profile) {
    return profile == null || profile.trim().isEmpty() ? activeProfile() : profile.trim();
  }

  private Map<String, Object> readYaml(String relativePath) {
    List<File> candidates =
        Arrays.asList(
            new File(rootDir, relativePath),
            new File("src/main/resources/prompts", relativePath),
            new File("agent-backend/src/main/resources/prompts", relativePath));
    for (File file : candidates) {
      if (!file.exists()) continue;
      try (InputStream input = new FileInputStream(file)) {
        Object loaded = yaml.load(input);
        return loaded instanceof Map
            ? (Map<String, Object>) loaded
            : Collections.<String, Object>emptyMap();
      } catch (Exception e) {
        log.warn(
            "Prompt YAML 加载失败，继续尝试后备路径: path={}, errorType={}", file, e.getClass().getSimpleName());
      }
    }
    try (InputStream input =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream("prompts/" + relativePath)) {
      if (input == null) return Collections.emptyMap();
      Object loaded = yaml.load(input);
      return loaded instanceof Map
          ? (Map<String, Object>) loaded
          : Collections.<String, Object>emptyMap();
    } catch (Exception e) {
      log.warn(
          "Classpath Prompt YAML 加载失败: path={}, errorType={}",
          relativePath,
          e.getClass().getSimpleName());
      return Collections.emptyMap();
    }
  }

  private Map<String, Object> asMap(Object value) {
    return value instanceof Map
        ? (Map<String, Object>) value
        : Collections.<String, Object>emptyMap();
  }

  private Map<String, Object> fallbackWorkbench() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("title", "智能工作台");
    data.put("description", "支持开放问答、资料分析、任务规划、内容生成和流程跟进。");
    data.put("placeholder", "例如：解释一个概念、生成方案、整理清单、分析材料或规划任务");
    data.put("quick_prompts", java.util.Arrays.asList("帮我解释一个行业概念", "帮我设计一份工作计划", "帮我整理一份清单"));
    return data;
  }
}
