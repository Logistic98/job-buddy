package com.jobbuddy.backend.modules.prompt.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 定义提示词注册表服务契约。
 */
public interface PromptRegistryService {
  /**
   * 获取当前启用的画像。
   *
   * @return 当前启用的画像
   */
  String activeProfile();

  /**
   * 获取 Web 工作台提示词。
   *
   * @param profile 画像
   * @return Web 工作台提示词
   */
  JsonNode frontendWorkbench(String profile);

  /**
   * 获取画像配置。
   *
   * @param profile 画像
   * @return 画像配置
   */
  JsonNode profileConfig(String profile);
}
