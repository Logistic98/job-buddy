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

/**
 * 从外置目录、仓库资源目录或 Classpath 加载 Prompt 配置。
 *
 * <p>外置配置优先，读取失败时按固定顺序降级，保证默认 Profile 始终可用。
 */
@Service
public class FilePromptRegistryService implements PromptRegistryService {
  private static final Logger log = LoggerFactory.getLogger(FilePromptRegistryService.class);
  private static final String DEFAULT_PROFILE = "default";
  private final File rootDir;
  private final Yaml yaml = new Yaml();
  private final JsonCodec jsonCodec = new JsonCodec();

  /**
   * 创建文件提示词注册表服务实例。
   */
  public FilePromptRegistryService() {
    String configured = System.getenv("JOB_BUDDY_PROMPT_DIR");
    this.rootDir =
        new File(
            configured == null || configured.trim().isEmpty()
                ? "src/main/resources/prompts"
                : configured.trim());
  }

  /**
   * 获取当前启用的画像。
   *
   * @return 当前启用的画像
   */
  @Override
  public String activeProfile() {
    String value = System.getenv("JOB_BUDDY_PROMPT_PROFILE");
    return value == null || value.trim().isEmpty() ? DEFAULT_PROFILE : value.trim();
  }

  /**
   * 获取 Web 工作台提示词。
   *
   * @param profile 画像
   * @return Web 工作台提示词
   */
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

  /**
   * 获取画像配置。
   *
   * @param profile 画像
   * @return 画像配置
   */
  @Override
  public JsonNode profileConfig(String profile) {
    String normalized = normalizeProfile(profile);
    Map<String, Object> data = readYaml("profiles/" + normalized + ".yaml");
    if (data.isEmpty() && !DEFAULT_PROFILE.equals(normalized))
      data = readYaml("profiles/" + DEFAULT_PROFILE + ".yaml");
    return jsonCodec.toTree(data);
  }

  /**
   * 规范化画像。
   *
   * @param profile 画像
   * @return 规范化后的画像
   */
  private String normalizeProfile(String profile) {
    return profile == null || profile.trim().isEmpty() ? activeProfile() : profile.trim();
  }

  /**
   * 读取 YAML 配置。
   *
   * @param relativePath 相对路径
   * @return YAML 配置
   */
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

  /**
   * 转换为映射。
   *
   * @param value 输入值
   * @return 转换后的键值映射
   */
  private Map<String, Object> asMap(Object value) {
    return value instanceof Map
        ? (Map<String, Object>) value
        : Collections.<String, Object>emptyMap();
  }

  /**
   * 获取降级结果工作台。
   *
   * @return 降级结果工作台
   */
  private Map<String, Object> fallbackWorkbench() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("title", "智能工作台");
    data.put("description", "支持开放问答、资料分析、任务规划、内容生成和流程跟进。");
    data.put("placeholder", "例如：解释一个概念、生成方案、整理清单、分析材料或规划任务");
    data.put("quick_prompts", java.util.Arrays.asList("帮我解释一个行业概念", "帮我设计一份工作计划", "帮我整理一份清单"));
    return data;
  }
}
