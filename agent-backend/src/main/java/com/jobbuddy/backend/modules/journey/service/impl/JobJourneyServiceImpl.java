package com.jobbuddy.backend.modules.journey.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.journey.dto.request.JobTargetRequest;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyAnalysisRequest;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyRecordRequest;
import com.jobbuddy.backend.modules.journey.dto.response.JobTargetResponse;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyAnalysisResponse;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyRecordResponse;
import com.jobbuddy.backend.modules.journey.repository.JobJourneyRepository;
import com.jobbuddy.backend.modules.journey.service.JobJourneyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 持久化用户求职旅程，并基于记录和显式输入计算进展摘要。
 */
@Service
public class JobJourneyServiceImpl implements JobJourneyService {
  private final JobJourneyRepository repository;
  private final JobBuddyProperties properties;
  private final JsonCodec jsonCodec;

  /**
   * 创建岗位求职旅程服务实例。
   *
   * @param repository 存储访问
   * @param properties 配置属性
   * @param jsonCodec JSON 编解码器
   */
  @Autowired
  public JobJourneyServiceImpl(
      JobJourneyRepository repository, JobBuddyProperties properties, JsonCodec jsonCodec) {
    this.repository = repository;
    this.properties = properties;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 创建岗位求职旅程服务实例。
   *
   * @param repository 存储访问
   * @param properties 配置属性
   */
  public JobJourneyServiceImpl(JobJourneyRepository repository, JobBuddyProperties properties) {
    this(repository, properties, new JsonCodec());
  }

  /**
   * 获取当前用户的求职目标。
   *
   * @param userId 用户标识
   * @return 目标
   */
  public JobTargetResponse getTarget(String userId) {
    return jsonCodec.convert(getTargetMap(userId), JobTargetResponse.class);
  }

  /**
   * 获取目标映射。
   *
   * @param userId 用户标识
   * @return 目标映射
   */
  private Map<String, Object> getTargetMap(String userId) {
    String effectiveUser = defaultUser(userId);
    Map<String, Object> target = repository.findTarget(effectiveUser);
    if (target != null) return target;
    Map<String, Object> seed = new LinkedHashMap<String, Object>();
    seed.put(
        "targetId", "target_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    seed.put("userId", effectiveUser);
    seed.put("companyNature", "互联网企业");
    seed.put("companyScale", "不限");
    seed.put("location", "上海");
    seed.put("salaryRange", "40k-50k");
    seed.put("domains", "大模型领域");
    seed.put("positions", "Java 大模型应用开发工程师");
    seed.put("preferredCompanies", "量化金融公司、米哈游、小红书、AI行业独角兽、小而精的AI创业公司、互联网大厂");
    seed.put("notes", "");
    repository.saveTarget(seed);
    return repository.findTarget(effectiveUser);
  }

  /**
   * 新增或更新当前用户的求职目标。
   *
   * @param userId 用户标识
   * @param request 请求对象
   * @return 保存后的目标
   */
  public JobTargetResponse saveTarget(String userId, JobTargetRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    String effectiveUser = defaultUser(userId);
    Map<String, Object> current = repository.findTarget(effectiveUser);
    Map<String, Object> target = new LinkedHashMap<String, Object>();
    target.put(
        "targetId",
        stringOrDefault(
            payload.get("targetId"),
            current == null
                ? "target_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : String.valueOf(current.get("targetId"))));
    target.put("userId", effectiveUser);
    target.put("companyNature", string(payload.get("companyNature")));
    target.put("companyScale", string(payload.get("companyScale")));
    target.put("location", string(payload.get("location")));
    target.put("salaryRange", string(payload.get("salaryRange")));
    target.put("domains", string(payload.get("domains")));
    target.put("positions", string(payload.get("positions")));
    target.put("preferredCompanies", string(payload.get("preferredCompanies")));
    target.put("notes", string(payload.get("notes")));
    repository.saveTarget(target);
    return jsonCodec.convert(repository.findTarget(effectiveUser), JobTargetResponse.class);
  }

  /**
   * 查询记录列表。
   *
   * @param userId 用户标识
   * @param keyword 关键词
   * @param status 状态
   * @param result 执行结果
   * @return 记录列表
   */
  public List<JourneyRecordResponse> listRecords(
      String userId, String keyword, String status, String result) {
    String effectiveUser = defaultUser(userId);
    return jsonCodec.convertList(
        repository.listRecords(effectiveUser, keyword, status, result),
        JourneyRecordResponse.class);
  }

  /**
   * 获取并校验当前用户所属的求职记录。
   *
   * @param recordId 记录标识
   * @param userId 用户标识
   * @return 记录
   */
  public JourneyRecordResponse getRecord(String recordId, String userId) {
    return jsonCodec.convert(requireOwnedRecord(recordId, userId), JourneyRecordResponse.class);
  }

  /**
   * 新增或更新当前用户的求职记录。
   *
   * @param userId 用户标识
   * @param request 请求对象
   * @param recordId 记录标识
   * @return 保存后的记录
   */
  public JourneyRecordResponse saveRecord(
      String userId, JourneyRecordRequest request, String recordId) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    String effectiveUser = defaultUser(userId);
    if (recordId != null && !recordId.trim().isEmpty()) requireOwnedRecord(recordId, effectiveUser);
    Map<String, Object> record = new LinkedHashMap<String, Object>();
    record.put(
        "recordId",
        stringOrDefault(
            recordId, "journey_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
    record.put("userId", effectiveUser);
    record.put("company", stringOrDefault(payload.get("company"), "未命名企业"));
    record.put("city", string(payload.get("city")));
    record.put("companyNature", string(payload.get("companyNature")));
    record.put("companyScale", string(payload.get("companyScale")));
    record.put("positionName", string(payload.get("positionName")));
    record.put("salaryRange", string(payload.get("salaryRange")));
    record.put("favoriteKey", string(payload.get("favoriteKey")));
    record.put("businessDirection", string(payload.get("businessDirection")));
    record.put("interviewRound", string(payload.get("interviewRound")));
    record.put("interviewTime", string(payload.get("interviewTime")));
    record.put("interviewContent", string(payload.get("interviewContent")));
    record.put("interviewFormat", string(payload.get("interviewFormat")));
    record.put("result", stringOrDefault(payload.get("result"), "跟进中"));
    record.put("reflection", string(payload.get("reflection")));
    record.put("jobDescription", string(payload.get("jobDescription")));
    record.put("interviewProcess", string(payload.get("interviewProcess")));
    record.put("nextAction", string(payload.get("nextAction")));
    record.put("status", stringOrDefault(payload.get("status"), "面试进展"));
    record.put("priority", stringOrDefault(payload.get("priority"), "中"));
    record.put("tags", normalizeTags(payload.get("tags")));
    record.put("enabled", Boolean.TRUE);
    repository.saveRecord(record);
    return jsonCodec.convert(
        repository.findRecord(String.valueOf(record.get("recordId"))), JourneyRecordResponse.class);
  }

  /**
   * 校验归属后删除求职记录。
   *
   * @param recordId 记录标识
   * @param userId 用户标识
   */
  public void deleteRecord(String recordId, String userId) {
    requireOwnedRecord(recordId, userId);
    repository.deleteRecord(recordId);
  }

  /**
   * 分析进度。
   *
   * @param userId 用户标识
   * @param request 请求对象
   * @return 进度分析结果
   */
  public JourneyAnalysisResponse analyzeProgress(String userId, JourneyAnalysisRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    String effectiveUser = defaultUser(userId);
    List<Map<String, Object>> records = repository.listRecords(effectiveUser, null, null, null);
    // 请求可限定参与分析的记录集合，并单独指定重点复盘对象。
    List<String> selectedRecordIds = normalizeStringList(payload.get("recordIds"));
    if (!selectedRecordIds.isEmpty()) {
      List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> row : records) {
        if (selectedRecordIds.contains(string(row.get("recordId")))) filtered.add(row);
      }
      records = filtered;
    }
    String recordId = string(payload.get("recordId"));
    Map<String, Object> focus = null;
    if (!recordId.isEmpty()) {
      for (Map<String, Object> row : records) {
        if (recordId.equals(string(row.get("recordId")))) {
          focus = row;
          break;
        }
      }
    }
    if (focus == null && !records.isEmpty()) focus = records.get(0);
    Map<String, Object> target = getTargetMap(effectiveUser);

    // 单次遍历汇总漏斗指标、面试轮次、业务方向和待跟进信号。
    int total = records.size();
    int active = 0, passed = 0, failed = 0, pending = 0, offer = 0, converted = 0, highPriority = 0;
    Map<String, Integer> roundCount = new LinkedHashMap<String, Integer>();
    Map<String, Integer> domainCount = new LinkedHashMap<String, Integer>();
    List<Map<String, Object>> followUps = new ArrayList<Map<String, Object>>();
    List<Map<String, Object>> weakSignals = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> row : records) {
      String result = string(row.get("result"));
      String status = string(row.get("status"));
      if ("通过".equals(result)) passed++;
      else if ("未通过".equals(result)) failed++;
      else if ("待反馈".equals(result) || "跟进中".equals(result)) pending++;
      boolean hasOffer = "Offer".equals(status) || result.contains("Offer");
      if (hasOffer) offer++;
      if ("通过".equals(result) || hasOffer) converted++;
      if (!"结束".equals(status) && !"未通过".equals(result) && !"已放弃".equals(result)) active++;
      if ("高".equals(row.get("priority"))) highPriority++;
      addCount(roundCount, stringOrDefault(row.get("interviewRound"), "未标注"));
      addCount(domainCount, stringOrDefault(row.get("businessDirection"), "未标注"));
      if (needsFollowUp(row)) followUps.add(row);
      if (hasWeakSignal(row)) weakSignals.add(row);
    }

    // 综合分只反映当前求职漏斗健康度，并限制在可解释的稳定区间。
    int score =
        Math.min(
            95,
            Math.max(
                35,
                45
                    + active * 8
                    + passed * 12
                    + offer * 18
                    + highPriority * 4
                    - failed * 6
                    - weakSignals.size() * 3));
    List<String> strengths = new ArrayList<String>();
    if (active > 0) strengths.add("当前仍有 " + active + " 条机会处于推进中，可以继续拉动反馈和后续轮次。 ");
    if (passed > 0) strengths.add("已有通过记录，说明简历和部分面试表现得到验证，建议复用对应准备材料。 ");
    if (!domainCount.isEmpty())
      strengths.add("求职方向集中在「" + topKey(domainCount) + "」，便于沉淀一套可复用的项目和技术表达。 ");
    if (strengths.isEmpty()) strengths.add("已开始建立求职台账，后续需要持续补全岗位 JD、面试内容和复盘。 ");

    List<String> risks = new ArrayList<String>();
    if (total == 0) risks.add("当前还没有进展记录，无法判断漏斗转化情况。先补充最近 3-5 条投递或面试记录。 ");
    if (pending > 0) risks.add("有 " + pending + " 条记录仍在跟进或待反馈，建议设置明确跟进时间，避免机会静默流失。 ");
    if (weakSignals.size() > 0)
      risks.add("有 " + weakSignals.size() + " 条记录缺少面试内容、复盘或下一步动作，后续难以针对性改进。 ");
    if (failed > passed && failed > 0) risks.add("未通过记录偏多，需要从技术短板、项目表达、岗位匹配三个维度拆解原因。 ");
    if (risks.isEmpty()) risks.add("当前记录没有明显风险，但仍建议每次面试后当天完成复盘。 ");

    // 建议围绕重点机会、待跟进事项和高频业务方向逐层生成。
    List<String> nextActions = new ArrayList<String>();
    if (focus != null) {
      nextActions.add(
          "针对「"
              + stringOrDefault(focus.get("company"), "该企业")
              + " - "
              + stringOrDefault(focus.get("positionName"), "该岗位")
              + "」，先补齐 JD、面试内容、复盘和下一步动作，再准备下一轮问题清单。 ");
    }
    if (!followUps.isEmpty())
      nextActions.add(
          "优先跟进「"
              + stringOrDefault(followUps.get(0).get("company"), "待反馈企业")
              + "」，用简短礼貌话术确认结果或下一轮安排。 ");
    nextActions.add(
        "把高频业务方向「"
            + (domainCount.isEmpty()
                ? stringOrDefault(target.get("domains"), "目标方向")
                : topKey(domainCount))
            + "」整理成 3 分钟项目介绍、核心难点、指标收益和追问答案。 ");
    nextActions.add("为下一场面试准备一份清单：岗位 JD 对齐点、项目亮点、技术短板补强、反问问题和期望薪资边界。 ");

    List<String> preparationPlan = new ArrayList<String>();
    preparationPlan.add("今天：补全最近记录的面试内容和复盘，标记每条记录的下一步动作。 ");
    preparationPlan.add("3 天内：围绕最高频方向整理 10 个技术追问和 5 个项目深挖问题。 ");
    preparationPlan.add("一周内：根据通过/未通过记录复盘投递画像，保留高匹配岗位，减少低匹配消耗。 ");

    Map<String, Object> metrics = new LinkedHashMap<String, Object>();
    metrics.put("total", total);
    metrics.put("active", active);
    metrics.put("passed", passed);
    metrics.put("failed", failed);
    metrics.put("pending", pending);
    metrics.put("offer", offer);
    metrics.put("score", score);
    metrics.put("topRound", topKey(roundCount));
    metrics.put("topDomain", topKey(domainCount));

    // 最终响应同时保留指标、风险、行动计划和可直接使用的跟进话术。
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("summary", buildAnalysisSummary(total, active, passed, failed, pending, score));
    result.put("metrics", metrics);
    result.put(
        "scoreGroups", buildScoreGroups(total, active, converted, pending, weakSignals.size()));
    result.put("strengths", strengths);
    result.put("risks", risks);
    result.put("nextActions", nextActions);
    result.put("preparationPlan", preparationPlan);
    result.put(
        "followUpMessage",
        buildFollowUpMessage(
            focus != null ? focus : (!followUps.isEmpty() ? followUps.get(0) : null)));
    result.put("generatedAt", java.time.Instant.now().toString());
    return jsonCodec.convert(result, JourneyAnalysisResponse.class);
  }

  /**
   * 校验并获取当前用户所属资源记录。
   *
   * @param recordId 记录标识
   * @param userId 用户标识
   * @return 校验后的并获取当前用户所属资源记录
   */
  private Map<String, Object> requireOwnedRecord(String recordId, String userId) {
    Map<String, Object> record = repository.findRecord(recordId);
    if (record == null) throw new IllegalArgumentException("求职记录不存在: " + recordId);
    String effectiveUser = defaultUser(userId);
    String owner = string(record.get("userId"));
    if (!effectiveUser.equals(owner)) throw new IllegalArgumentException("无权操作该求职记录");
    return record;
  }

  /**
   * 获取默认用户。
   *
   * @param userId 用户标识
   * @return 默认用户
   */
  private String defaultUser(String userId) {
    return (userId == null || userId.isEmpty()) ? properties.getDefaultUserId() : userId;
  }

  /**
   * 将可空值转换为空值安全的字符串。
   *
   * @param value 输入值
   * @return 字符串值
   */
  private String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * 获取字符串或默认值。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 字符串或默认值
   */
  private String stringOrDefault(Object value, String fallback) {
    String text = string(value).trim();
    return text.isEmpty() ? fallback : text;
  }

  /**
   * 新增数量。
   *
   * @param counts 分类计数
   * @param key 业务键
   */
  private void addCount(Map<String, Integer> counts, String key) {
    if (key == null || key.trim().isEmpty()) key = "未标注";
    counts.put(key, Integer.valueOf(counts.containsKey(key) ? counts.get(key).intValue() + 1 : 1));
  }

  /**
   * 获取计数最高的分类键。
   *
   * @param counts 分类计数
   * @return 计数最高的分类键
   */
  private String topKey(Map<String, Integer> counts) {
    String best = "";
    int bestCount = 0;
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      if (entry.getValue() != null && entry.getValue().intValue() > bestCount) {
        best = entry.getKey();
        bestCount = entry.getValue().intValue();
      }
    }
    return best;
  }

  /**
   * 判断是否需要跟进。
   *
   * @param row 查询行
   * @return 是否需要跟进
   */
  private boolean needsFollowUp(Map<String, Object> row) {
    String result = string(row.get("result"));
    return "待反馈".equals(result)
        || "跟进中".equals(result)
        || string(row.get("nextAction")).trim().isEmpty();
  }

  /**
   * 判断是否存在弱信号。
   *
   * @param row 查询行
   * @return 是否存在弱信号
   */
  private boolean hasWeakSignal(Map<String, Object> row) {
    return string(row.get("interviewContent")).trim().isEmpty()
        || string(row.get("reflection")).trim().isEmpty()
        || string(row.get("nextAction")).trim().isEmpty();
  }

  /**
   * 构建分析摘要。
   *
   * @param total 总数
   * @param active 进行中数量
   * @param passed 通过数量
   * @param failed 失败数量
   * @param pending 待处理数量
   * @param score 分数
   * @return 分析摘要
   */
  private String buildAnalysisSummary(
      int total, int active, int passed, int failed, int pending, int score) {
    if (total == 0) return "当前还没有可分析的面试进展。建议先录入投递、笔试、面试和反馈记录，系统会基于漏斗状态给出建议。";
    return "当前共分析 "
        + total
        + " 条求职进展，其中推进中 "
        + active
        + " 条、通过 "
        + passed
        + " 条、未通过 "
        + failed
        + " 条、待反馈/跟进中 "
        + pending
        + " 条。综合推进健康度约为 "
        + score
        + " 分，建议优先补齐复盘和下一步动作。";
  }

  /**
   * 构建评分分组。
   *
   * @param total 总数
   * @param active 进行中数量
   * @param converted 转化数量
   * @param pending 待处理数量
   * @param weakSignals 弱信号数量
   * @return 评分分组
   */
  private List<Map<String, Object>> buildScoreGroups(
      int total, int active, int converted, int pending, int weakSignals) {
    List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
    int activityScore = ratioScore(active, total);
    int conversionScore = ratioScore(converted, total);
    int followUpScore = ratioScore(Math.max(0, total - pending), total);
    int completenessScore = ratioScore(Math.max(0, total - weakSignals), total);
    groups.add(scoreGroup("activity", "推进活跃度", activityScore, active + " / " + total + " 条机会仍在推进"));
    groups.add(scoreGroup("conversion", "结果转化", conversionScore, converted + " 条记录已通过或进入 Offer"));
    groups.add(scoreGroup("followUp", "跟进及时性", followUpScore, pending + " 条记录仍需跟进反馈"));
    groups.add(
        scoreGroup("completeness", "记录完整度", completenessScore, weakSignals + " 条记录缺少内容、复盘或下一步"));
    return groups;
  }

  /**
   * 计算比例评分。
   *
   * @param numerator 分子
   * @param denominator 分母
   * @return 比例得分
   */
  private int ratioScore(int numerator, int denominator) {
    if (denominator <= 0) return 0;
    return Math.min(100, Math.max(0, (int) Math.round(numerator * 100.0d / denominator)));
  }

  /**
   * 获取评分分组。
   *
   * @param key 业务键
   * @param label 展示标签
   * @param score 分数
   * @param description 说明文本
   * @return 评分分组
   */
  private Map<String, Object> scoreGroup(String key, String label, int score, String description) {
    Map<String, Object> group = new LinkedHashMap<String, Object>();
    group.put("key", key);
    group.put("label", label);
    group.put("score", score);
    group.put("description", description);
    return group;
  }

  /**
   * 规范化字符串列表。
   *
   * @param raw 原始数据
   * @return 规范化后的字符串列表
   */
  private List<String> normalizeStringList(Object raw) {
    List<String> result = new ArrayList<String>();
    if (raw instanceof List) {
      for (Object item : (List) raw) {
        String value = string(item).trim();
        if (!value.isEmpty() && !result.contains(value)) result.add(value);
      }
    } else if (raw != null) {
      String[] parts = String.valueOf(raw).split("[,，、\\s]+");
      for (String part : parts) {
        String value = part.trim();
        if (!value.isEmpty() && !result.contains(value)) result.add(value);
      }
    }
    return result;
  }

  /**
   * 构建跟进提示消息。
   *
   * @param row 查询行
   * @return 跟进消息
   */
  private String buildFollowUpMessage(Map<String, Object> row) {
    if (row == null) return "您好，我想跟进一下之前沟通的岗位进展。如果有后续安排或需要补充材料，我可以及时配合提供，谢谢。";
    String company = stringOrDefault(row.get("company"), "贵司");
    String position = stringOrDefault(row.get("positionName"), "相关岗位");
    String round = stringOrDefault(row.get("interviewRound"), "面试");
    return "您好，我想跟进一下「"
        + company
        + " - "
        + position
        + "」"
        + round
        + " 后续进展。如果有下一轮安排或需要补充材料，我可以及时配合提供，谢谢。";
  }

  /**
   * 规范化标签列表。
   *
   * @param raw 原始数据
   * @return 规范化后的标签列表
   */
  private List<Map<String, Object>> normalizeTags(Object raw) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    if (raw instanceof List) {
      for (Object item : (List) raw) addTag(result, item);
    } else if (raw != null) {
      String[] parts = String.valueOf(raw).split("[,，、\\s]+");
      for (String part : parts) addTag(result, part);
    }
    return result;
  }

  /**
   * 添加非空标签。
   *
   * @param result 执行结果
   * @param raw 原始数据
   */
  private void addTag(List<Map<String, Object>> result, Object raw) {
    String label = raw instanceof Map ? string(((Map) raw).get("label")) : string(raw);
    if (label.trim().isEmpty()) return;
    Map<String, Object> tag = new LinkedHashMap<String, Object>();
    tag.put("label", label.trim());
    result.add(tag);
  }
}
