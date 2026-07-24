package com.jobbuddy.backend.modules.interview.service.impl;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.interview.dto.request.*;
import com.jobbuddy.backend.modules.interview.dto.response.*;
import com.jobbuddy.backend.modules.interview.repository.InterviewRepository;
import com.jobbuddy.backend.modules.interview.service.InterviewService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewServiceImpl implements InterviewService {
  private final InterviewRepository interviewRepository;
  private final InterviewCodeRunner interviewCodeRunner;
  private final JsonCodec jsonCodec;
  private final AgentIntegrationService agentIntegrationService;

  @Autowired
  public InterviewServiceImpl(
      InterviewRepository interviewRepository,
      InterviewCodeRunner interviewCodeRunner,
      JsonCodec jsonCodec,
      AgentIntegrationService agentIntegrationService) {
    this.interviewRepository = interviewRepository;
    this.interviewCodeRunner = interviewCodeRunner;
    this.jsonCodec = jsonCodec;
    this.agentIntegrationService = agentIntegrationService;
  }

  public InterviewServiceImpl(
      InterviewRepository interviewRepository, InterviewCodeRunner interviewCodeRunner) {
    this(interviewRepository, interviewCodeRunner, new JsonCodec(), null);
  }

  public List<InterviewQuestionResponse> listQuestions(String keyword, String category) {
    return jsonCodec.convertList(
        interviewRepository.listQuestions(keyword, category), InterviewQuestionResponse.class);
  }

  public InterviewQuestionPageResponse pageQuestions(
      String keyword,
      String bankType,
      String category,
      String difficulty,
      Integer pageValue,
      Integer sizeValue) {
    int page = pageValue == null ? 1 : Math.max(1, pageValue.intValue());
    int size = sizeValue == null ? 20 : Math.max(1, Math.min(sizeValue.intValue(), 100));
    String normalizedBankType = normalizeBankType(bankType, null);
    int total =
        interviewRepository.countQuestions(keyword, normalizedBankType, category, difficulty);
    List<Map<String, Object>> items =
        interviewRepository.listQuestions(
            keyword, normalizedBankType, category, difficulty, page, size);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("total", Integer.valueOf(total));
    result.put("page", Integer.valueOf(page));
    result.put("size", Integer.valueOf(size));
    result.put("pages", Integer.valueOf((int) Math.ceil(total / (double) size)));
    return jsonCodec.convert(result, InterviewQuestionPageResponse.class);
  }

  public InterviewQuestionMetaResponse questionMeta(String bankType) {
    Map<String, Object> meta = interviewRepository.questionMeta(normalizeBankType(bankType, null));
    List<Map<String, Object>> bankTypes = new ArrayList<Map<String, Object>>();
    addBankTypeMeta(bankTypes, "leetcode", "算法题库");
    addBankTypeMeta(bankTypes, "qa", "问答题库");
    meta.put("bankTypeOptions", bankTypes);
    return jsonCodec.convert(meta, InterviewQuestionMetaResponse.class);
  }

  public InterviewQuestionResponse saveQuestion(
      InterviewQuestionRequest request, String questionId) {
    return jsonCodec.convert(
        saveQuestionMap(jsonCodec.toMap(request), questionId), InterviewQuestionResponse.class);
  }

  private Map<String, Object> saveQuestionMap(Map<String, Object> payload, String questionId) {
    if (payload == null) payload = Collections.emptyMap();
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put(
        "questionId",
        questionId == null || questionId.trim().isEmpty()
            ? "iq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            : questionId);
    question.put("title", required(payload, "title", "题目标题不能为空"));
    question.put("content", required(payload, "content", "题目内容不能为空"));
    String questionType = defaultString(payload.get("questionType"), "单选");
    String bankType =
        normalizeBankType(
            stringValue(payload.get("bankType")), "编程题".equals(questionType) ? "leetcode" : "qa");
    if ("leetcode".equals(bankType)) questionType = "编程题";
    question.put("bankType", bankType);
    question.put("answer", stringValue(payload.get("answer")));
    question.put("category", defaultString(payload.get("category"), "通用"));
    question.put("difficulty", defaultString(payload.get("difficulty"), "中等"));
    question.put("questionType", questionType);
    question.put("tags", normalizeTags(payload.get("tags")));
    question.put(
        "codingMeta", normalizeCodingMeta(payload.get("codingMeta"), "leetcode".equals(bankType)));
    question.put("enabled", Boolean.TRUE);
    interviewRepository.saveQuestion(question);
    return interviewRepository.findQuestion(String.valueOf(question.get("questionId")));
  }

  public void deleteQuestion(String questionId) {
    interviewRepository.deleteQuestion(questionId);
  }

  @SuppressWarnings("unchecked")
  public InterviewBatchResponse batchQuestions(InterviewBatchRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Object idsValue = payload == null ? null : payload.get("questionIds");
    if (!(idsValue instanceof List)) throw new IllegalArgumentException("请选择要批量操作的题目");
    List<String> questionIds = new ArrayList<String>();
    for (Object id : (List<Object>) idsValue) {
      if (id != null && !String.valueOf(id).trim().isEmpty()) questionIds.add(String.valueOf(id));
    }
    if (questionIds.isEmpty()) throw new IllegalArgumentException("请选择要批量操作的题目");
    String action = defaultString(payload.get("action"), "update");
    if ("delete".equals(action)) {
      interviewRepository.batchDeleteQuestions(questionIds);
    } else {
      Map<String, Object> fields = new LinkedHashMap<String, Object>();
      String category = stringValue(payload.get("category"));
      String difficulty = stringValue(payload.get("difficulty"));
      Object tags = payload.get("tags");
      if (category != null && !category.trim().isEmpty()) fields.put("category", category.trim());
      if (difficulty != null && !difficulty.trim().isEmpty())
        fields.put("difficulty", difficulty.trim());
      if (tags != null) fields.put("tags", normalizeTags(tags));
      if (fields.isEmpty()) throw new IllegalArgumentException("请至少填写分类、难度或标签中的一项");
      interviewRepository.batchUpdateQuestions(questionIds, fields);
    }
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("count", Integer.valueOf(questionIds.size()));
    result.put("action", action);
    return jsonCodec.convert(result, InterviewBatchResponse.class);
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public InterviewImportResponse importQuestions(InterviewImportRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Object value = payload == null ? null : payload.get("items");
    List<Object> rows = value instanceof List ? (List<Object>) value : Collections.emptyList();
    int saved = 0;
    List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
    for (Object row : rows) {
      if (!(row instanceof Map)) continue;
      Map<String, Object> item = saveQuestionMap((Map<String, Object>) row, null);
      items.add(item);
      saved++;
    }
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("count", Integer.valueOf(saved));
    result.put("items", items);
    return jsonCodec.convert(result, InterviewImportResponse.class);
  }

  public InterviewGenerateResponse generateQuestions(InterviewGenerateRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    String topic = stringValue(payload.get("topic"));
    String category = required(payload, "category", "分类不能为空");
    String difficulty = required(payload, "difficulty", "难度不能为空");
    String questionType = required(payload, "questionType", "题型不能为空");
    String bankType =
        normalizeBankType(
            stringValue(payload.get("bankType")), "编程题".equals(questionType) ? "leetcode" : "qa");
    if (!"leetcode".equals(bankType) && !"qa".equals(bankType))
      throw new IllegalArgumentException("不支持的题库类型");
    String requirements = stringValue(payload.get("requirements"));
    String sourceUrl = stringValue(payload.get("sourceUrl"));
    String documentText = stringValue(payload.get("documentText"));
    if (documentText != null && documentText.length() > 20000)
      documentText = documentText.substring(0, 20000);
    if (isBlank(topic) && isBlank(sourceUrl) && isBlank(documentText) && isBlank(requirements))
      throw new IllegalArgumentException(
          "leetcode".equals(bankType) ? "请提供算法主题、LeetCode 链接、题面或算法资料" : "请提供知识主题、参考文本、出题要求或问答资料");
    int count = intValue(payload.get("count"), 5);
    if (count < 1 || count > 20) throw new IllegalArgumentException("生成数量需在 1-20 之间");
    String language = defaultString(payload.get("language"), "python").toLowerCase();
    if ("leetcode".equals(bankType)
        && !"python".equals(language)
        && !"java".equals(language)
        && !"javascript".equals(language))
      throw new IllegalArgumentException("代码语言仅支持 python、java、javascript");
    if (agentIntegrationService == null) throw new IllegalStateException("智能生成服务未配置");

    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
    arguments.put("topic", topic);
    arguments.put("bank_type", bankType);
    arguments.put("category", category);
    arguments.put("difficulty", difficulty);
    arguments.put("question_type", "leetcode".equals(bankType) ? "编程题" : questionType);
    arguments.put("language", language);
    arguments.put("count", Integer.valueOf(count));
    arguments.put("requirements", requirements);
    arguments.put("source_url", sourceUrl);
    arguments.put("source_text", documentText);
    Map<String, Object> toolResult =
        agentIntegrationService.invokeRuntimeTool("interview_question_generate", arguments);
    if (toolResult == null || toolResult.isEmpty())
      throw new IllegalStateException("智能生成服务暂不可用，请稍后重试");
    if (Boolean.FALSE.equals(toolResult.get("success"))) {
      String message = stringValue(toolResult.get("error"));
      throw new IllegalArgumentException(isBlank(message) ? "候选题生成失败，请调整资料后重试" : message);
    }
    Object dataValue = toolResult.get("data");
    Map<String, Object> generated =
        dataValue instanceof Map
            ? (Map<String, Object>) dataValue
            : toolResult.get("output") instanceof Map
                ? (Map<String, Object>) toolResult.get("output")
                : Collections.<String, Object>emptyMap();
    Object rowsValue = generated.get("items");
    if (!(rowsValue instanceof List)) throw new IllegalArgumentException("智能生成结果缺少候选题列表，请重新生成");
    List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
    for (Object row : (List<Object>) rowsValue) {
      if (!(row instanceof Map)) throw new IllegalArgumentException("智能生成的候选题结构不正确，请重新生成");
      items.add(
          normalizeGeneratedQuestion(
              (Map<String, Object>) row, bankType, category, difficulty, questionType));
    }
    if (items.size() != count) throw new IllegalArgumentException("智能生成的候选题数量不完整，请重新生成");
    return generatedResponse(items);
  }

  private Map<String, Object> normalizeGeneratedQuestion(
      Map<String, Object> payload,
      String bankType,
      String category,
      String difficulty,
      String questionType) {
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put("title", required(payload, "title", "候选题标题不能为空"));
    question.put("content", required(payload, "content", "候选题内容不能为空"));
    question.put("bankType", bankType);
    question.put("answer", stringValue(payload.get("answer")));
    question.put("category", defaultString(payload.get("category"), category));
    question.put("difficulty", defaultString(payload.get("difficulty"), difficulty));
    question.put("questionType", "leetcode".equals(bankType) ? "编程题" : questionType);
    question.put("tags", normalizeTags(payload.get("tags")));
    if ("leetcode".equals(bankType))
      question.put("codingMeta", normalizeCodingMeta(payload.get("codingMeta"), true));
    return question;
  }

  private InterviewGenerateResponse generatedResponse(List<Map<String, Object>> items) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("count", Integer.valueOf(items.size()));
    result.put("items", items);
    return jsonCodec.convert(result, InterviewGenerateResponse.class);
  }

  @SuppressWarnings("unchecked")
  public InterviewExamResponse createRandomExam(
      String tenantId, String userId, InterviewExamRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    List<Map<String, Object>> selected = new ArrayList<Map<String, Object>>();
    Object questionIdsValue = payload.get("questionIds");
    Object rulesValue = payload.get("rules");
    if (questionIdsValue instanceof List && !((List) questionIdsValue).isEmpty()) {
      java.util.Set<String> used = new java.util.HashSet<String>();
      for (Object idValue : (List) questionIdsValue) {
        String questionId = stringValue(idValue);
        if (questionId == null || questionId.trim().isEmpty() || used.contains(questionId))
          continue;
        Map<String, Object> question = interviewRepository.findQuestion(questionId.trim());
        if (question == null) continue;
        selected.add(question);
        used.add(questionId);
        if (selected.size() >= 50) break;
      }
    } else if (rulesValue instanceof List && !((List) rulesValue).isEmpty()) {
      java.util.Set<String> used = new java.util.HashSet<String>();
      for (Object ruleValue : (List) rulesValue) {
        if (!(ruleValue instanceof Map)) continue;
        Map<String, Object> rule = (Map<String, Object>) ruleValue;
        int count = normalizeExamCount(rule.get("count"), 0);
        if (count <= 0) continue;
        String bankType = normalizeBankType(stringValue(rule.get("bankType")), null);
        List<Map<String, Object>> pool =
            interviewRepository.findEnabled(
                bankType,
                stringValue(rule.get("category")),
                stringValue(rule.get("difficulty")),
                stringValue(rule.get("questionType")));
        Collections.shuffle(pool);
        int picked = 0;
        for (Map<String, Object> question : pool) {
          String questionId = String.valueOf(question.get("questionId"));
          if (used.contains(questionId)) continue;
          selected.add(question);
          used.add(questionId);
          picked++;
          if (selected.size() >= 50 || picked >= count) break;
        }
      }
    } else {
      String bankType = normalizeBankType(stringValue(payload.get("bankType")), null);
      String category = stringValue(payload.get("category"));
      String difficulty = stringValue(payload.get("difficulty"));
      int count = normalizeExamCount(payload.get("count"), 5);
      List<Map<String, Object>> pool =
          interviewRepository.findEnabled(
              bankType, category, difficulty, stringValue(payload.get("questionType")));
      Collections.shuffle(pool);
      selected =
          pool.size() > count ? new ArrayList<Map<String, Object>>(pool.subList(0, count)) : pool;
    }
    if (selected.size() > 50)
      selected = new ArrayList<Map<String, Object>>(selected.subList(0, 50));
    if (selected.isEmpty()) throw new IllegalArgumentException("当前出题策略下没有可用练习题，请先维护题库或调整组合规则。 ");
    String examId = "practice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String title = defaultString(payload.get("title"), "随机组卷");
    int durationMinutes = normalizeDuration(payload.get("durationMinutes"));
    Map<String, Object> strategy = new LinkedHashMap<String, Object>();
    strategy.put(
        "mode",
        questionIdsValue instanceof List && !((List) questionIdsValue).isEmpty()
            ? "manual"
            : "random");
    strategy.put(
        "questionIds",
        questionIdsValue instanceof List ? questionIdsValue : Collections.emptyList());
    strategy.put("rules", rulesValue instanceof List ? rulesValue : Collections.emptyList());
    strategy.put("durationMinutes", Integer.valueOf(durationMinutes));
    strategy.put("showAnswer", Boolean.valueOf(booleanValue(payload.get("showAnswer"))));
    interviewRepository.createExam(
        tenantId, userId, examId, title, durationMinutes, strategy, selected);
    return jsonCodec.convert(
        interviewRepository.findExam(tenantId, userId, examId), InterviewExamResponse.class);
  }

  private int normalizeExamCount(Object value, int defaultValue) {
    int count = intValue(value, defaultValue);
    if (count <= 0) return defaultValue;
    return Math.min(count, 50);
  }

  private int normalizeDuration(Object value) {
    int minutes = intValue(value, 30);
    if (minutes <= 0) return 30;
    return Math.min(minutes, 240);
  }

  public InterviewExamResponse getExam(String tenantId, String userId, String examId) {
    return jsonCodec.convert(getExamMap(tenantId, userId, examId), InterviewExamResponse.class);
  }

  private Map<String, Object> getExamMap(String tenantId, String userId, String examId) {
    Map<String, Object> exam = interviewRepository.findExam(tenantId, userId, examId);
    if (exam == null) throw new IllegalArgumentException("练习不存在");
    return exam;
  }

  public List<InterviewExamResponse> listExams(String tenantId, String userId) {
    return jsonCodec.convertList(
        interviewRepository.listExams(tenantId, userId), InterviewExamResponse.class);
  }

  public InterviewCodeRunResponse runCode(InterviewCodeRunRequest request) {
    return jsonCodec.convert(
        interviewCodeRunner.run(jsonCodec.toMap(request)), InterviewCodeRunResponse.class);
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public InterviewExamSubmitResponse submitExam(
      String tenantId, String userId, String examId, InterviewExamSubmitRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Map<String, Object> exam = interviewRepository.findExamForUpdate(tenantId, userId, examId);
    if (exam == null) throw new IllegalArgumentException("练习不存在");
    if ("submitted".equals(String.valueOf(exam.get("status")))) {
      return jsonCodec.convert(exam, InterviewExamSubmitResponse.class);
    }
    Object answersValue = payload.get("answers");
    Object codingResultsValue = payload.get("codingResults");
    Map<String, Object> answers =
        answersValue instanceof Map
            ? (Map<String, Object>) answersValue
            : Collections.<String, Object>emptyMap();
    Map<String, Object> codingResults =
        codingResultsValue instanceof Map
            ? (Map<String, Object>) codingResultsValue
            : Collections.<String, Object>emptyMap();
    List<Map<String, Object>> questions = (List<Map<String, Object>>) exam.get("questions");
    int answered = 0;
    double totalScore = 0;
    for (Map<String, Object> question : questions) {
      String questionId = String.valueOf(question.get("questionId"));
      String userAnswer = stringValue(answers.get(questionId));
      if (userAnswer != null && !userAnswer.trim().isEmpty()) answered++;
      boolean correct = evaluateQuestion(question, userAnswer, codingResults.get(questionId));
      double score = correct ? 100.0 / Math.max(1, questions.size()) : 0.0;
      totalScore += score;
      interviewRepository.saveExamAnswer(examId, questionId, userAnswer, correct, score);
    }
    interviewRepository.finishExam(examId, answered, Math.round(totalScore * 100.0) / 100.0);
    return jsonCodec.convert(
        getExamMap(tenantId, userId, examId), InterviewExamSubmitResponse.class);
  }

  private boolean evaluateQuestion(
      Map<String, Object> question, String userAnswer, Object codingResult) {
    if ("编程题".equals(stringValue(question.get("questionType")))
        || "leetcode".equals(stringValue(question.get("bankType")))) {
      if (codingResult instanceof Boolean) return ((Boolean) codingResult).booleanValue();
      if (codingResult instanceof Map) {
        Object passed = ((Map) codingResult).get("passed");
        if (passed instanceof Boolean) return ((Boolean) passed).booleanValue();
        if (passed != null) return Boolean.parseBoolean(String.valueOf(passed));
      }
      return false;
    }
    if ("简答".equals(stringValue(question.get("questionType")))) {
      return evaluateShortAnswer(userAnswer, stringValue(question.get("answer")));
    }
    return evaluate(userAnswer, stringValue(question.get("answer")));
  }

  /** 简答题按参考答案关键片段覆盖率做轻量判分，命中一半以上视为通过。 */
  private boolean evaluateShortAnswer(String userAnswer, String expectedAnswer) {
    if (userAnswer == null
        || userAnswer.trim().isEmpty()
        || expectedAnswer == null
        || expectedAnswer.trim().isEmpty()) return false;
    String user = normalize(userAnswer);
    List<String> segments = new ArrayList<String>();
    for (String part : expectedAnswer.split("[；;。\\n、，,]+")) {
      String segment = normalize(part);
      if (segment.length() >= 2) segments.add(segment);
    }
    if (segments.isEmpty()) return evaluate(userAnswer, expectedAnswer);
    int hit = 0;
    for (String segment : segments) {
      if (user.contains(segment)) hit++;
    }
    return hit * 2 >= segments.size();
  }

  private boolean evaluate(String userAnswer, String expectedAnswer) {
    if (userAnswer == null
        || userAnswer.trim().isEmpty()
        || expectedAnswer == null
        || expectedAnswer.trim().isEmpty()) return false;
    String user = normalize(userAnswer);
    String expected = normalize(expectedAnswer);
    return user.equals(expected) || user.contains(expected) || expected.contains(user);
  }

  private String normalize(String value) {
    return value == null
        ? ""
        : value.toLowerCase().replaceAll("\\s+", "").replace("。", "").replace("，", ",").trim();
  }

  private void addBankTypeMeta(List<Map<String, Object>> options, String value, String label) {
    Map<String, Object> option = new LinkedHashMap<String, Object>();
    option.put("value", value);
    option.put("label", label);
    options.add(option);
  }

  private String normalizeBankType(String value, String defaultValue) {
    String text = value == null ? null : value.trim().toLowerCase();
    if (text == null || text.isEmpty()) return defaultValue;
    if (text.contains("leetcode") || text.contains("leet-code") || text.contains("编程"))
      return "leetcode";
    if (text.contains("qa") || text.contains("八股") || text.contains("选择")) return "qa";
    return text;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> normalizeCodingMeta(Object value, boolean codingRequired) {
    if (!(value instanceof Map)) {
      if (codingRequired) throw new IllegalArgumentException("算法题必须维护 codingMeta 字段");
      return new LinkedHashMap<String, Object>();
    }
    Map<String, Object> source = (Map<String, Object>) value;
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    String language = defaultString(source.get("language"), "python").toLowerCase();
    if (!"python".equals(language) && !"java".equals(language) && !"javascript".equals(language)) {
      throw new IllegalArgumentException("codingMeta.language 仅支持 python、java、javascript");
    }
    String functionName = required(source, "functionName", "codingMeta.functionName 不能为空");
    int parameterCount = intValue(source.get("parameterCount"), 0);
    if (parameterCount < 1 || parameterCount > 10)
      throw new IllegalArgumentException("codingMeta.parameterCount 需在 1-10 之间");
    Object testsValue = source.get("tests");
    if (testsValue != null && !(testsValue instanceof List)) {
      throw new IllegalArgumentException("codingMeta.tests 必须是数组");
    }
    List<Object> sourceTests =
        testsValue instanceof List ? (List<Object>) testsValue : Collections.<Object>emptyList();
    if (codingRequired && sourceTests.isEmpty()) {
      throw new IllegalArgumentException("codingMeta.tests 至少需要 1 条测试用例");
    }
    List<Map<String, Object>> tests = new ArrayList<Map<String, Object>>();
    for (Object item : sourceTests) {
      if (!(item instanceof Map)) throw new IllegalArgumentException("codingMeta.tests 每项必须是对象");
      Map<String, Object> test = new LinkedHashMap<String, Object>((Map<String, Object>) item);
      if (!(test.get("args") instanceof List) || !test.containsKey("expected")) {
        throw new IllegalArgumentException("codingMeta.tests 每项必须包含 args 数组和 expected 字段");
      }
      tests.add(test);
      if (tests.size() >= 20) break;
    }
    result.put("language", language);
    result.put("functionName", functionName);
    result.put("parameterCount", Integer.valueOf(parameterCount));
    result.put("signature", stringValue(source.get("signature")));
    result.put("template", required(source, "template", "codingMeta.template 不能为空"));
    result.put("tests", tests);
    return result;
  }

  private String required(Map<String, Object> payload, String key, String message) {
    String value = stringValue(payload == null ? null : payload.get(key));
    if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
    return value.trim();
  }

  private String defaultString(Object value, String defaultValue) {
    String text = stringValue(value);
    return text == null || text.trim().isEmpty() ? defaultValue : text.trim();
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private boolean booleanValue(Object value) {
    if (value instanceof Boolean) return ((Boolean) value).booleanValue();
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private int intValue(Object value, int defaultValue) {
    if (value instanceof Number) return ((Number) value).intValue();
    try {
      return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
    } catch (Exception e) {
      return defaultValue;
    }
  }

  private List<Map<String, Object>> normalizeTags(Object value) {
    List<Map<String, Object>> tags = new ArrayList<Map<String, Object>>();
    if (value instanceof List) {
      for (Object item : (List) value) addTag(tags, item);
    } else if (value != null) {
      String[] parts = String.valueOf(value).split("[,，、\\s]+");
      for (String part : parts) addTag(tags, part);
    }
    return tags;
  }

  private void addTag(List<Map<String, Object>> tags, Object item) {
    if (item == null) return;
    String text;
    if (item instanceof Map) {
      Map map = (Map) item;
      Object label = map.get("label");
      if (label == null) label = map.get("name");
      if (label == null) label = map.get("value");
      text = label == null ? "" : String.valueOf(label).trim();
    } else {
      text = cleanTagText(String.valueOf(item));
    }
    if (text.isEmpty()) return;
    Map<String, Object> tag = new LinkedHashMap<String, Object>();
    tag.put("label", text);
    tags.add(tag);
  }

  private String cleanTagText(String value) {
    if (value == null) return "";
    String text = value.trim();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "(?:^|[\\{,\\s])label\\s*[:=]\\s*([^,}\\]]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text);
    if (matcher.find()) text = matcher.group(1).trim();
    return text.replaceAll("^[\\{\\[\\(]+|[\\}\\]\\)]+$", "")
        .replaceAll("(?i)^label\\s*[:=]\\s*", "")
        .replaceAll("^['\"]|['\"]$", "")
        .trim();
  }
}
