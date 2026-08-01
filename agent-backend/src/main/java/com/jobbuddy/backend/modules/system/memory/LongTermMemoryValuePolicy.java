package com.jobbuddy.backend.modules.system.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 长期记忆自动沉淀的精确率优先价值门控。
 *
 * <p>显式记忆命令和设置页手动写入不经过本策略；这里只判断普通对话是否包含可跨任务复用的稳定信息。
 */
public final class LongTermMemoryValuePolicy {
  private static final List<String> SAVE_OPT_OUT_SIGNALS =
      List.of("不要记住", "别记住", "不用记住", "无需记住", "不希望你记住", "不想让你记住");
  private static final List<String> TRANSIENT_TASK_SIGNALS =
      List.of(
          "markdown",
          "mermaid",
          "latex",
          "代码块",
          "代码示例",
          "流程图",
          "渲染",
          "输出",
          "生成",
          "执行工具",
          "执行任何工具",
          "调用工具",
          "不要解释",
          "只回复",
          "直接回答",
          "直接给出",
          "查找",
          "搜索",
          "分析当前",
          "当前简历",
          "当前岗位",
          "这个岗位",
          "当前文件",
          "这个文件",
          "本次任务",
          "页面验收");
  private static final Pattern STABLE_IDENTITY_PATTERN =
      Pattern.compile(
          "(?:^|[\\s，,。.!！？?；;：:])"
              + "(?:(?:记住[\\s，,：:]*)?我叫(?!什么|啥|谁|你|他|她|大家)"
              + "|我的(?:名字|姓名)(?:是|叫)(?!什么|啥|谁|哪位)"
              + "|(?:以后|今后)(?:请)?叫我(?!怎么|什么|啥)"
              + "|请叫我(?!怎么|什么|啥)"
              + "|称呼我为(?!什么|啥|谁|哪位))"
              + "(?=[\\p{L}\\p{N}_])");
  private static final Pattern DURABLE_INTERACTION_PREFERENCE_PATTERN =
      Pattern.compile("^(?:以后|今后|后续|每次|始终|长期).{0,16}(?:回答|回复|称呼|使用|采用|沟通).{1,80}");
  private static final Pattern INTERVIEW_REVIEW_PATTERN =
      Pattern.compile(
          "(?:面试复盘|面试总结|上次面试|本次面试).{0,120}" + "(?:薄弱|不足|不会|没答|回答不|需要加强|待加强|需要改进|掌握不牢|擅长|表现)");
  private static final Pattern CAREER_CONTEXT_PATTERN =
      Pattern.compile(
          "(?:求职|职业|岗位|职位|工作|办公|远程|城市|地区|薪资|工资|待遇|行业|公司|团队|"
              + "后端|前端|开发方向|技术方向|大模型|agent|云原生|外包|驻场|夜班|加班|出差|双休|大厂)",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern PERSONAL_CAREER_PREFERENCE_PATTERN =
      Pattern.compile(
          "(?:^|[\\s，,。.!！？?；;：:])我(?:的)?(?:.{0,16})"
              + "(?:求职目标(?:是|为)?|职业目标(?:是|为)?|目标(?:是|为)|偏好|期望|希望|喜欢|倾向|"
              + "优先|不考虑|不接受|排除|只考虑)");
  private static final Pattern DIRECT_CAREER_PREFERENCE_PATTERN =
      Pattern.compile(
          "^(?:求职目标(?:是|为)|职业目标(?:是|为)|目标(?:是|为)|"
              + "(?:求职|职业|岗位|工作)偏好(?:是|为|：|:)?|偏好(?:是|为|：|:)|期望(?:是|为|：|:)|"
              + "优先(?:考虑|选择|投递|看)|排除|不考虑|不接受|"
              + "不要(?:外包|驻场|夜班|加班|出差|大小周|单休)|只考虑)");
  private static final Pattern STABLE_CAREER_FACT_PATTERN =
      Pattern.compile("(?:^|[\\s，,。.!！？?；;：:])我(?:目前|现在|长期|一直|有|从事|擅长|常驻|居住).{1,80}");

  private LongTermMemoryValuePolicy() {}

  /**
   * 判断普通对话是否包含值得自动沉淀的长期信息。
   *
   * @param message 用户消息
   * @return 是否允许自动写入长期记忆
   */
  public static boolean shouldAutoCapture(String message) {
    String text = message == null ? "" : message.trim();
    if (text.length() < 4 || containsAny(text, SAVE_OPT_OUT_SIGNALS)) return false;
    if (DURABLE_INTERACTION_PREFERENCE_PATTERN.matcher(text).find()) return true;

    String normalized = text.toLowerCase(Locale.ROOT);
    if (containsAny(normalized, TRANSIENT_TASK_SIGNALS)) return false;
    if (STABLE_IDENTITY_PATTERN.matcher(text).find()) return true;
    if (INTERVIEW_REVIEW_PATTERN.matcher(text).find()) return true;
    if (!CAREER_CONTEXT_PATTERN.matcher(text).find()) return false;
    return PERSONAL_CAREER_PREFERENCE_PATTERN.matcher(text).find()
        || DIRECT_CAREER_PREFERENCE_PATTERN.matcher(text).find()
        || STABLE_CAREER_FACT_PATTERN.matcher(text).find();
  }

  private static boolean containsAny(String text, List<String> signals) {
    for (String signal : signals) {
      if (text.contains(signal)) return true;
    }
    return false;
  }
}
