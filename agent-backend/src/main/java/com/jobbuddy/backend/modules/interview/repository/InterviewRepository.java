package com.jobbuddy.backend.modules.interview.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.interview.mapper.InterviewMapper;
import java.sql.Clob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;

/**
 * 面试题与模拟试卷的仓储适配器。
 *
 * <p>Mapper 使用 {@code tagsJson} 等数据库字段，本类在返回 Service 前将其规范为 API 结构。
 */
@Repository
public class InterviewRepository {
  private static final Pattern TAG_LABEL_PATTERN =
      Pattern.compile("(?:^|[\\{,\\s])label\\s*[:=]\\s*([^,}\\]]+)", Pattern.CASE_INSENSITIVE);

  private final InterviewMapper mapper;
  private final JsonCodec jsonCodec;

  /**
   * 创建面试存储访问实例。
   *
   * @param mapper 数据映射
   * @param jsonCodec JSON 编解码器
   */
  public InterviewRepository(InterviewMapper mapper, JsonCodec jsonCodec) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 查询题目列表。
   *
   * @param tenantId 租户标识
   * @param keyword 关键词
   * @param category 分类
   * @return 题目列表
   */
  public List<Map<String, Object>> listQuestions(String tenantId, String keyword, String category) {
    return listQuestions(tenantId, keyword, null, category, null, 1, 200);
  }

  /**
   * 查询题目列表。
   *
   * @param tenantId 租户标识
   * @param keyword 关键词
   * @param bankType 题库类型
   * @param category 分类
   * @param page 分页
   * @param size 大小
   * @return 题目列表
   */
  public List<Map<String, Object>> listQuestions(
      String tenantId, String keyword, String bankType, String category, int page, int size) {
    return listQuestions(tenantId, keyword, bankType, category, null, page, size);
  }

  /**
   * 查询题目列表。
   *
   * @param tenantId 租户标识
   * @param keyword 关键词
   * @param bankType 题库类型
   * @param category 分类
   * @param difficulty 难度
   * @param page 分页
   * @param size 大小
   * @return 题目列表
   */
  public List<Map<String, Object>> listQuestions(
      String tenantId,
      String keyword,
      String bankType,
      String category,
      String difficulty,
      int page,
      int size) {
    int normalizedSize = Math.max(1, Math.min(size, 100));
    int offset = Math.max(0, page - 1) * normalizedSize;
    List<Map<String, Object>> rows =
        mapper.listQuestions(
            tenantId,
            like(keyword),
            trim(bankType),
            trim(category),
            trim(difficulty),
            normalizedSize,
            offset);
    for (Map<String, Object> row : rows) {
      hydrateQuestion(row);
    }
    return rows;
  }

  /**
   * 统计题目列表。
   *
   * @param tenantId 租户标识
   * @param keyword 关键词
   * @param category 分类
   * @return 统计数量
   */
  public int countQuestions(String tenantId, String keyword, String category) {
    return countQuestions(tenantId, keyword, null, category, null);
  }

  /**
   * 统计题目列表。
   *
   * @param tenantId 租户标识
   * @param keyword 关键词
   * @param bankType 题库类型
   * @param category 分类
   * @param difficulty 难度
   * @return 统计数量
   */
  public int countQuestions(
      String tenantId, String keyword, String bankType, String category, String difficulty) {
    return mapper.countQuestions(
        tenantId, like(keyword), trim(bankType), trim(category), trim(difficulty));
  }

  /**
   * 查找启用状态。
   *
   * @param tenantId 租户标识
   * @param category 分类
   * @param difficulty 难度
   * @return 启用状态
   */
  public List<Map<String, Object>> findEnabled(
      String tenantId, String category, String difficulty) {
    return findEnabled(tenantId, null, category, difficulty, null);
  }

  /**
   * 查找启用状态。
   *
   * @param tenantId 租户标识
   * @param bankType 题库类型
   * @param category 分类
   * @param difficulty 难度
   * @param questionType 题目类型
   * @return 启用状态
   */
  public List<Map<String, Object>> findEnabled(
      String tenantId, String bankType, String category, String difficulty, String questionType) {
    List<Map<String, Object>> rows =
        mapper.findEnabled(
            tenantId, trim(bankType), trim(category), trim(difficulty), trim(questionType));
    for (Map<String, Object> row : rows) {
      hydrateQuestion(row);
    }
    return rows;
  }

  /**
   * 构造题目元数据。
   *
   * @param tenantId 租户标识
   * @param bankType 题库类型
   * @return 题目元数据
   */
  public Map<String, Object> questionMeta(String tenantId, String bankType) {
    Map<String, Object> meta = new LinkedHashMap<String, Object>();
    meta.put("bankTypes", mapper.listBankTypes(tenantId));
    meta.put("categories", mapper.listCategories(tenantId, trim(bankType)));
    meta.put("difficulties", mapper.listDifficulties(tenantId, trim(bankType)));
    meta.put("questionTypes", mapper.listQuestionTypes(tenantId, trim(bankType)));
    return meta;
  }

  /**
   * 查找题目。
   *
   * @param tenantId 租户标识
   * @param questionId 题目标识
   * @return 题目
   */
  public Map<String, Object> findQuestion(String tenantId, String questionId) {
    return hydrateQuestion(mapper.findQuestion(tenantId, questionId));
  }

  /**
   * 保存题目。
   *
   * @param tenantId 租户标识
   * @param question 题目
   */
  public void saveQuestion(String tenantId, Map<String, Object> question) {
    question.put("tenantId", tenantId);
    question.put("tagsJson", jsonCodec.toJson(question.get("tags")));
    question.put("codingMetaJson", jsonCodec.toJson(question.get("codingMeta")));
    question.put("enabled", Boolean.valueOf(!Boolean.FALSE.equals(question.get("enabled"))));
    question.put("updatedAt", Timestamp.from(Instant.now()));

    if (mapper.countQuestion(tenantId, question.get("questionId")) > 0) {
      mapper.updateQuestion(question);
    } else {
      mapper.insertQuestion(question);
    }
  }

  /**
   * 删除题目。
   *
   * @param tenantId 租户标识
   * @param questionId 题目标识
   */
  public void deleteQuestion(String tenantId, String questionId) {
    mapper.softDeleteQuestion(tenantId, questionId, Timestamp.from(Instant.now()));
  }

  /**
   * 批量删除题目。
   *
   * @param tenantId 租户标识
   * @param questionIds 题目标识列表
   * @return 实际删除数量
   */
  public int batchDeleteQuestions(String tenantId, List<String> questionIds) {
    Timestamp now = Timestamp.from(Instant.now());
    int affected = 0;
    for (String questionId : questionIds) {
      affected += mapper.softDeleteQuestion(tenantId, questionId, now);
    }
    return affected;
  }

  /**
   * 批量更新题目。
   *
   * @param tenantId 租户标识
   * @param questionIds 题目标识列表
   * @param fields 待更新字段
   * @return 实际更新数量
   */
  public int batchUpdateQuestions(
      String tenantId, List<String> questionIds, Map<String, Object> fields) {
    Timestamp now = Timestamp.from(Instant.now());
    int affected = 0;
    for (String questionId : questionIds) {
      int questionAffected = 0;
      if (fields.containsKey("category")) {
        questionAffected =
            Math.max(
                questionAffected,
                mapper.updateQuestionCategory(tenantId, questionId, fields.get("category"), now));
      }
      if (fields.containsKey("difficulty")) {
        questionAffected =
            Math.max(
                questionAffected,
                mapper.updateQuestionDifficulty(
                    tenantId, questionId, fields.get("difficulty"), now));
      }
      if (fields.containsKey("tags")) {
        questionAffected =
            Math.max(
                questionAffected,
                mapper.updateQuestionTags(
                    tenantId, questionId, jsonCodec.toJson(fields.get("tags")), now));
      }
      affected += questionAffected;
    }
    return affected;
  }

  /**
   * 创建考试。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @param title 标题
   * @param durationMinutes 时长分钟
   * @param strategy 抽题策略
   * @param recorded 是否展示为练习记录
   * @param questions 题目列表
   */
  public void createExam(
      String tenantId,
      String userId,
      String examId,
      String title,
      int durationMinutes,
      Object strategy,
      boolean recorded,
      List<Map<String, Object>> questions) {
    Timestamp now = Timestamp.from(Instant.now());
    Timestamp expiresAt =
        Timestamp.from(now.toInstant().plus(Duration.ofMinutes(Math.max(1, durationMinutes))));
    mapper.insertExam(
        tenantId,
        userId,
        examId,
        title,
        "running",
        questions.size(),
        0,
        null,
        durationMinutes,
        jsonCodec.toJson(strategy),
        recorded,
        now,
        expiresAt);

    int order = 1;
    for (Map<String, Object> question : questions) {
      mapper.insertExamQuestion(examId, question.get("questionId"), order++);
    }
  }

  /**
   * 查找考试。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @return 考试
   */
  public Map<String, Object> findExam(String tenantId, String userId, String examId) {
    return hydrateOwnedExam(mapper.findExam(tenantId, userId, examId), examId);
  }

  /**
   * 查询待更新的考试记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @return 待更新的考试记录
   */
  public Map<String, Object> findExamForUpdate(String tenantId, String userId, String examId) {
    return hydrateOwnedExam(mapper.findExamForUpdate(tenantId, userId, examId), examId);
  }

  /**
   * 补全所属考试。
   *
   * @param exam 考试
   * @param examId 考试标识
   * @return 补全后的记录
   */
  private Map<String, Object> hydrateOwnedExam(Map<String, Object> exam, String examId) {
    if (exam == null) return null;
    hydrateExam(exam);
    exam.put("questions", examQuestions(examId));
    return exam;
  }

  /**
   * 查询考试记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 考试列表
   */
  public List<Map<String, Object>> listExams(String tenantId, String userId) {
    List<Map<String, Object>> rows = mapper.listExams(tenantId, userId);
    for (Map<String, Object> row : rows) {
      hydrateExam(row);
    }
    return rows;
  }

  /**
   * 删除用户所属练习及其级联题目关系。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 练习标识
   * @return 是否删除
   */
  public boolean deleteExam(String tenantId, String userId, String examId) {
    return mapper.deleteExam(tenantId, userId, examId) > 0;
  }

  /**
   * 查询考试题目列表。
   *
   * @param examId 考试标识
   * @return 考试题目列表
   */
  public List<Map<String, Object>> examQuestions(String examId) {
    List<Map<String, Object>> rows = mapper.examQuestions(examId);
    for (Map<String, Object> row : rows) {
      hydrateQuestion(row);
    }
    return rows;
  }

  /**
   * 保存考试答案。
   *
   * @param examId 考试标识
   * @param questionId 题目标识
   * @param answer 答案
   * @param correct 是否回答正确
   * @param score 评分
   */
  public void saveExamAnswer(
      String examId, String questionId, String answer, boolean correct, double score) {
    mapper.saveExamAnswer(examId, questionId, answer, correct, score);
  }

  /**
   * 完成考试并写入提交结果。
   *
   * @param examId 考试标识
   * @param answeredCount 已答题数量
   * @param score 评分
   */
  public void finishExam(String examId, int answeredCount, double score) {
    mapper.finishExam(examId, answeredCount, score, Timestamp.from(Instant.now()));
  }

  /**
   * 补全题目。
   *
   * @param item 数据项
   * @return 补全后的记录
   */
  private Map<String, Object> hydrateQuestion(Map<String, Object> item) {
    if (item == null) {
      return null;
    }

    item.put("content", string(item.get("content")));
    item.put("answer", string(item.get("answer")));
    if (item.containsKey("userAnswer")) {
      item.put("userAnswer", string(item.get("userAnswer")));
    }
    item.put("tags", normalizeTags(jsonCodec.toMapList(string(item.get("tagsJson")))));
    item.put("codingMeta", jsonCodec.toMap(string(item.get("codingMetaJson"))));
    item.remove("tagsJson");
    item.remove("codingMetaJson");
    normalizeTime(item, "createdAt");
    normalizeTime(item, "updatedAt");
    return item;
  }

  /**
   * 规范化标签列表。
   *
   * @param raw 原始数据
   * @return 规范化标签列表
   */
  private List<Map<String, Object>> normalizeTags(List<Map<String, Object>> raw) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    if (raw == null) {
      return result;
    }

    for (Map<String, Object> row : raw) {
      Object value = row == null ? null : row.get("label");
      if (value == null && row != null) {
        value = row.get("name");
      }
      if (value == null && row != null) {
        value = row.get("value");
      }

      String label = cleanTagText(value == null ? "" : String.valueOf(value));
      if (label.isEmpty()) {
        continue;
      }

      Map<String, Object> tag = new LinkedHashMap<String, Object>();
      tag.put("label", label);
      result.add(tag);
    }
    return result;
  }

  /**
   * 清理题目标签文本。
   *
   * @param value 待处理值
   * @return 清理后的标签文本
   */
  private String cleanTagText(String value) {
    if (value == null) {
      return "";
    }

    String text = value.trim();
    Matcher matcher = TAG_LABEL_PATTERN.matcher(text);
    if (matcher.find()) {
      text = matcher.group(1).trim();
    }
    return text.replaceAll("^[\\{\\[\\(]+|[\\}\\]\\)]+$", "")
        .replaceAll("(?i)^label\\s*[:=]\\s*", "")
        .replaceAll("^['\"]|['\"]$", "")
        .trim();
  }

  /**
   * 补全考试。
   *
   * @param exam 考试
   */
  private void hydrateExam(Map<String, Object> exam) {
    if (exam == null) {
      return;
    }
    normalizeTime(exam, "startedAt");
    normalizeTime(exam, "expiresAt");
    normalizeTime(exam, "submittedAt");
    exam.put("strategy", jsonCodec.toMap(string(exam.get("strategyJson"))));
    exam.remove("strategyJson");
    Object expires = exam.get("expiresAt");
    long remaining = 0L;
    if (expires instanceof Instant && !"submitted".equals(String.valueOf(exam.get("status")))) {
      remaining = Math.max(0L, Duration.between(Instant.now(), (Instant) expires).getSeconds());
    }
    exam.put("remainingSeconds", Long.valueOf(remaining));
  }

  /**
   * 裁剪文本首尾空白。
   *
   * @param value 待处理值
   * @return 裁剪后的文本
   */
  private String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  /**
   * 构造模糊匹配表达式。
   *
   * @param value 待处理值
   * @return 模糊匹配表达式
   */
  private String like(String value) {
    return value == null || value.trim().isEmpty() ? null : "%" + value.trim().toLowerCase() + "%";
  }

  /**
   * 将输入转换为字符串。
   *
   * @param value 待处理值
   * @return 字符串值
   */
  private String string(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Clob) {
      try {
        Clob clob = (Clob) value;
        return clob.getSubString(1, (int) clob.length());
      } catch (Exception ignored) {
        return "";
      }
    }
    return String.valueOf(value);
  }

  /**
   * 规范化时间。
   *
   * @param item 数据项
   * @param key 键
   */
  private void normalizeTime(Map<String, Object> item, String key) {
    Object value = item.get(key);
    if (value instanceof Timestamp) {
      item.put(key, ((Timestamp) value).toInstant());
    }
  }
}
