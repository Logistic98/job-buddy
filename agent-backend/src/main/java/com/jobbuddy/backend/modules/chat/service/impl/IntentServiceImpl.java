package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.client.IntentClient;
import com.jobbuddy.backend.modules.chat.service.IntentService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 对话主链路前置的快速意图预分类器。
 *
 * <p>优先调用 agent-intent（规则 → 加权打分 → 可选 LLM 兜底 → 默认澄清）拿到结构化预判； 当 agent-intent
 * 不可用或返回空时，退化到本地关键词规则分类，并在 secondary 中携带降级标记 {@code
 * intent_service_unavailable_local_fallback}，便于上游与观测识别降级路径。
 */
@Service
public class IntentServiceImpl implements IntentService {
  private final IntentClient intentClient;

  public IntentServiceImpl(IntentClient intentClient) {
    this.intentClient = intentClient;
  }

  public IntentResult classify(String message) {
    IntentResult result = intentClient.classify(message);
    if (result != null) {
      return result;
    }
    return classifyLocally(message);
  }

  /** 本地规则降级分类：仅在 agent-intent 不可用时使用，保证预分类链路可用。 secondary 中携带降级标记，便于上游与观测识别降级路径。 */
  private IntentResult classifyLocally(String message) {
    String text = message == null ? "" : message.trim();
    List<String> secondary = Collections.singletonList("intent_service_unavailable_local_fallback");
    IntentResult result;
    if (text.isEmpty()) {
      result = new IntentResult("unknown", "unknown", 0.0, secondary, "low", true, "clarify");
    } else {
      String lower = text.toLowerCase(java.util.Locale.ROOT);
      if (containsAny(lower, "删库", "rm -rf", "删除所有", "提权", "破解", "攻击")) {
        result =
            new IntentResult(
                "security", "high_risk_request", 0.9, secondary, "high", true, "reject");
      } else if (containsAny(
          lower, "岗位", "职位", "找工作", "招聘", "简历", "面试", "投递", "薪资", "offer", "内推", "boss直聘")) {
        result =
            new IntentResult("job", "job.consult", 0.6, secondary, "low", false, "direct_answer");
      } else {
        result =
            new IntentResult(
                "open_domain", "general.chat", 0.5, secondary, "low", false, "direct_answer");
      }
    }
    result.setRouter("backend_fallback");
    return result;
  }

  private boolean containsAny(String text, String... keywords) {
    for (String keyword : keywords) {
      if (text.contains(keyword)) return true;
    }
    return false;
  }
}
