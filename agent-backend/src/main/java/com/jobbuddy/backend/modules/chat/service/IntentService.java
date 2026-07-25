package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.vo.IntentResult;

/**
 * 定义意图服务契约。
 */
public interface IntentService {
  /**
   * 识别用户意图。
   *
   * @param message 消息内容
   * @return 分类结果
   */
  IntentResult classify(String message);
}
