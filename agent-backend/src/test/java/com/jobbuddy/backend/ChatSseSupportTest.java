package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.util.ChatSseSupport;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 ChatSseSupport 的核心行为、异常路径与边界条件。
 */
class ChatSseSupportTest {

  /**
   * 验证 ChatSseSupport 中记忆的核心业务契约。
   */
  @Test
  void shouldCaptureLongTermMemoryOnlyForStableSignals() {
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("排除外包岗位，偏好大厂"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("不要外包"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("我希望做后端"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("我喜欢远程办公"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("我倾向 Java 后端开发"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("记住我叫小明"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("请记住：我的名字是小明"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("我的名字是小明"));
    assertEquals(true, ChatSseSupport.shouldCaptureLongTermMemory("以后请叫我小明"));
    assertEquals(false, ChatSseSupport.shouldCaptureLongTermMemory("不要记住我叫小明"));
    assertEquals(false, ChatSseSupport.shouldCaptureLongTermMemory("你记住我叫什么了吗"));
    assertEquals(false, ChatSseSupport.shouldCaptureLongTermMemory("我的名字是什么？"));
    assertEquals(false, ChatSseSupport.shouldCaptureLongTermMemory("我叫你帮我看下这个岗位"));
    assertEquals(false, ChatSseSupport.shouldCaptureLongTermMemory("请叫我怎么处理这个问题"));
    assertEquals(false, ChatSseSupport.shouldCaptureLongTermMemory("帮我看下这个岗位"));
    assertEquals(false, ChatSseSupport.shouldCaptureLongTermMemory(null));
  }

  /**
   * 验证格式、渲染、工具和当前任务指令不会因为包含泛化关键词而污染长期记忆。
   */
  @Test
  void shouldRejectTransientInstructionsThatContainGenericMemoryKeywords() {
    assertFalse(
        ChatSseSupport.shouldCaptureLongTermMemory("请直接输出 Markdown 示例，不要执行任何工具，也不要解释渲染方法。"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("我希望你只输出 Mermaid 和 LaTeX，不要调用工具。"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("分析当前简历与目标岗位的匹配度"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("优先输出 javascript 代码块"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("不要解释，直接给答案"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("不要只看岗位标题，帮我分析公司发展"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("优先分析岗位职责，不要展开公司背景"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("请输出一句：我的名字是小明"));
    assertFalse(ChatSseSupport.shouldCaptureLongTermMemory("请解释“请记住”是什么意思"));
  }

  /**
   * 验证跨任务可复用的求职偏好、约束、目标和复盘仍会进入长期记忆。
   */
  @Test
  void shouldCaptureOnlyDurableCareerSignals() {
    assertTrue(ChatSseSupport.shouldCaptureLongTermMemory("我的求职目标是上海 Agent 开发岗位"));
    assertTrue(ChatSseSupport.shouldCaptureLongTermMemory("我希望长期从事 Java 后端开发"));
    assertTrue(ChatSseSupport.shouldCaptureLongTermMemory("优先考虑杭州 30-40K 的后端岗位"));
    assertTrue(ChatSseSupport.shouldCaptureLongTermMemory("排除外包、驻场和长期夜班岗位"));
    assertTrue(ChatSseSupport.shouldCaptureLongTermMemory("面试复盘：分布式事务回答不完整，需要加强"));
  }

  /**
   * 验证 ChatSseSupport 中记忆的检索、筛选与排序规则。
   */
  @Test
  void isMemoryNoiseEventShouldMatchOnlyIdAndName() {
    Map<String, Object> noise = new LinkedHashMap<String, Object>();
    noise.put("id", "memory_read");
    assertTrue(ChatSseSupport.isMemoryNoiseEvent(noise));
    Map<String, Object> safe = new LinkedHashMap<String, Object>();
    safe.put("id", "job_search");
    safe.put("summary", "读取记忆面板");
    assertFalse(ChatSseSupport.isMemoryNoiseEvent(safe));
  }

  /**
   * 验证 ChatSseSupport 中工具的核心业务契约。
   */
  @Test
  void accumulateToolEventShouldMergeByIdAndDropNoise() {
    ChatSessionState state = new ChatSessionState();
    Map<String, Object> first = new LinkedHashMap<String, Object>();
    first.put("id", "job_search");
    first.put("status", "running");
    ChatSseSupport.accumulateToolEvent(state, first);
    Map<String, Object> update = new LinkedHashMap<String, Object>();
    update.put("id", "job_search");
    update.put("status", "success");
    ChatSseSupport.accumulateToolEvent(state, update);
    Map<String, Object> noise = new LinkedHashMap<String, Object>();
    noise.put("id", "记忆写入");
    ChatSseSupport.accumulateToolEvent(state, noise);
    assertEquals(1, state.toolEvents.size());
    assertEquals("success", state.toolEvents.get(0).get("status"));
  }

  /**
   * 验证 ChatSseSupport 中岗位的核心业务契约。
   */
  @Test
  void withSelectedJobContextShouldAppendKnownFields() {
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("jobName", "后端工程师");
    job.put("brandName", "示例公司");
    String out = ChatSseSupport.withSelectedJobContext("帮我分析", job);
    assertTrue(out.contains("岗位名称: 后端工程师"));
    assertTrue(out.contains("公司: 示例公司"));
    assertEquals("原文", ChatSseSupport.withSelectedJobContext("原文", null));
  }

  /**
   * 验证 ChatSseSupport 中运行时的输入校验与拒绝边界。
   */
  @Test
  void intentFromRuntimeShouldDefaultMissingFields() {
    Map<String, Object> directive = new LinkedHashMap<String, Object>();
    directive.put("domain", "job");
    directive.put("intent", "job.recommend");
    directive.put("confidence", 0.8);
    directive.put("router", "semantic_config_shortcut");
    IntentResult intent = ChatSseSupport.intentFromRuntime(directive);
    assertEquals("job", intent.getDomain());
    assertEquals("job.recommend", intent.getIntent());
    assertEquals(0.8, intent.getConfidence(), 1e-9);
    assertEquals("semantic_config_shortcut", intent.getRouter());
    assertEquals("clarify", intent.getNextAction());
  }

  /**
   * 验证 ChatSseSupport 中运行时的核心业务契约。
   */
  @Test
  void intentHintShouldPreserveRouterForRuntimeObservability() {
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.8,
            java.util.Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs");
    intent.setRouter("llm");

    assertEquals("llm", ChatSseSupport.intentHint(intent).get("router"));
  }

  /**
   * 验证 ChatSseSupport 的检索、筛选与排序规则。
   */
  @Test
  void matchesCapabilityShouldCompareActionAndIntent() {
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            java.util.Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            null);
    assertTrue(
        ChatSseSupport.matchesCapability(
            "call_get_recommend_jobs", intent, "call_get_recommend_jobs"));
    assertTrue(ChatSseSupport.matchesCapability("other", intent, "job.recommend"));
    assertFalse(ChatSseSupport.matchesCapability("other", intent, "resume.match"));
  }

  /**
   * 验证 ChatSseSupport 的失败恢复、超时与降级边界。
   */
  @Test
  void directiveActionShouldFallBackToIntent() {
    Map<String, Object> directive = new LinkedHashMap<String, Object>();
    directive.put("next_action", "call_login");
    assertEquals("call_login", ChatSseSupport.directiveAction(directive, null));
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            java.util.Collections.<String>emptyList(),
            "low",
            false,
            "run_job_recommend",
            null);
    assertEquals(
        "run_job_recommend",
        ChatSseSupport.directiveAction(new LinkedHashMap<String, Object>(), intent));
  }

  /**
   * 验证 ChatSseSupport 中岗位的核心业务契约。
   */
  @Test
  void manualTargetJobsShouldRequireSufficientJd() {
    assertTrue(ChatSseSupport.manualTargetJobs("后端", "短描述", null).isEmpty());
    List<Map<String, Object>> jobs =
        ChatSseSupport.manualTargetJobs(
            "后端工程师", "负责后端服务开发，熟悉分布式系统与高并发架构设计，要求三年以上微服务与消息队列实战经验", null);
    assertEquals(1, jobs.size());
    assertEquals("user_provided_jd", jobs.get(0).get("source"));
  }

  /**
   * 验证 ChatSseSupport 中简历的检索、筛选与排序规则。
   */
  @Test
  void resumeMatchSummaryShouldReadTopScore() {
    Map<String, Object> top = new LinkedHashMap<String, Object>();
    top.put("score", "88");
    top.put("recommendation", "推荐投递");
    Map<String, Object> match = new LinkedHashMap<String, Object>();
    match.put("matches", Arrays.asList(top));
    assertTrue(ChatSseSupport.resumeMatchSummary(match).contains("88"));

    Map<String, Object> insufficient = new LinkedHashMap<String, Object>();
    insufficient.put("score_confidence", "low");
    insufficient.put("recommendation", "证据不足");
    insufficient.put("limitations", Arrays.asList("简历项目证据不足，无法判断个人贡献"));
    match.put("matches", Arrays.asList(insufficient));
    String insufficientSummary = ChatSseSupport.resumeMatchSummary(match);
    assertTrue(insufficientSummary.contains("证据不足"));
    assertTrue(insufficientSummary.contains("简历项目证据不足"));
    assertFalse(insufficientSummary.contains("缺少完整 JD"));
    assertEquals(
        "简历匹配已完成，匹配详情已同步到当前岗位卡片。",
        ChatSseSupport.resumeMatchSummary(new LinkedHashMap<String, Object>()));
  }

  /**
   * 验证 ChatSseSupport 中简历的检索、筛选与排序规则。
   */
  @Test
  void resumeMatchSummaryShouldExposeResolvedResumeAndSelectedJob() {
    Map<String, Object> top = new LinkedHashMap<String, Object>();
    top.put("score", 86);
    top.put("score_confidence", "high");
    top.put("recommendation", "推荐");
    top.put("reasoning", "Go 与 Kubernetes 工程化经历能够覆盖岗位核心要求。。");
    top.put("hits", Arrays.asList("具备 Go 后端经验。", "具备 Kubernetes 项目经验。"));
    top.put("gaps", Arrays.asList("行业经验需要补充。"));
    Map<String, Object> match = new LinkedHashMap<String, Object>();
    match.put("matches", Arrays.asList(top));
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("jobName", "云原生平台开发岗");
    job.put("company", "星河云科（虚构）");

    String summary = ChatSseSupport.resumeMatchSummary(match, "5年经验示例简历.pdf", job, true);

    assertTrue(summary.contains("5年经验示例简历.pdf"));
    assertTrue(summary.contains("星河云科（虚构） / 云原生平台开发岗"));
    assertTrue(summary.contains("重新评估上一轮岗位"));
    assertTrue(summary.contains("## 匹配结论"));
    assertTrue(summary.contains("## 核心判断"));
    assertTrue(summary.contains("## 主要匹配"));
    assertTrue(summary.contains("- **匹配评分：** **86/100**"));
    assertTrue(summary.contains("- 具备 Kubernetes 项目经验"));
    assertFalse(summary.contains("。。"));
    assertFalse(summary.contains("经验。\n"));
  }

  /**
   * 验证 ChatSseSupport 中运行时的失败恢复、超时与降级边界。
   */
  @Test
  void summarizeRuntimeResultShouldPreferError() {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("error", "runtime down");
    assertEquals("runtime down", ChatSseSupport.summarizeRuntimeResult(result));
    assertEquals("空响应", ChatSseSupport.summarizeRuntimeResult(null));
  }
}
