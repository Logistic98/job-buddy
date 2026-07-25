package com.jobbuddy.backend.modules.journey.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.journey.mapper.JobJourneyMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 求职旅程持久化适配器，负责 JSON 字段编解码与数据库时间类型归一化。
 */
@Repository
public class JobJourneyRepository {
  private final JobJourneyMapper mapper;
  private final JsonCodec jsonCodec;

  /**
   * 创建岗位求职旅程存储访问实例。
   *
   * @param mapper 数据映射
   * @param jsonCodec JSON 编解码器
   */
  public JobJourneyRepository(JobJourneyMapper mapper, JsonCodec jsonCodec) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 查找目标。
   *
   * @param userId 用户标识
   * @return 目标
   */
  public Map<String, Object> findTarget(String userId) {
    Map<String, Object> row = mapper.findTarget(userId);
    normalizeTime(row, "updatedAt");
    return row;
  }

  /**
   * 按目标标识新增或更新求职目标。
   *
   * @param target 求职目标数据
   */
  public void saveTarget(Map<String, Object> target) {
    target.put("updatedAt", Timestamp.from(Instant.now()));
    if (mapper.countTarget(target.get("targetId")) > 0) mapper.updateTarget(target);
    else mapper.insertTarget(target);
  }

  /**
   * 查询记录列表。
   *
   * @param userId 用户标识
   * @param keyword 关键词
   * @param status 状态
   * @param result 结果
   * @return 记录列表
   */
  public List<Map<String, Object>> listRecords(
      String userId, String keyword, String status, String result) {
    String q =
        keyword == null || keyword.trim().isEmpty()
            ? null
            : "%" + keyword.trim().toLowerCase() + "%";
    List<Map<String, Object>> rows = mapper.listRecords(userId, q, trim(status), trim(result));
    for (Map<String, Object> row : rows) hydrateRecord(row);
    return rows;
  }

  /**
   * 查找记录。
   *
   * @param recordId 记录标识
   * @return 记录
   */
  public Map<String, Object> findRecord(String recordId) {
    return hydrateRecord(mapper.findRecord(recordId));
  }

  /**
   * 编码标签并按记录标识新增或更新求职记录。
   *
   * @param record 记录
   */
  public void saveRecord(Map<String, Object> record) {
    record.put("tagsJson", jsonCodec.toJson(record.get("tags")));
    record.put("enabled", Boolean.valueOf(!Boolean.FALSE.equals(record.get("enabled"))));
    record.put("updatedAt", Timestamp.from(Instant.now()));
    if (mapper.countRecord(record.get("recordId")) > 0) mapper.updateRecord(record);
    else mapper.insertRecord(record);
  }

  /**
   * 软删除指定求职记录。
   *
   * @param recordId 记录标识
   */
  public void deleteRecord(String recordId) {
    mapper.deleteRecord(recordId, Timestamp.from(Instant.now()));
  }

  /**
   * 补全记录。
   *
   * @param item 数据项
   * @return 补全后的记录
   */
  private Map<String, Object> hydrateRecord(Map<String, Object> item) {
    if (item == null) return null;
    item.put("tags", jsonCodec.toMapList(string(item.get("tagsJson"))));
    item.remove("tagsJson");
    normalizeTime(item, "createdAt");
    normalizeTime(item, "updatedAt");
    return item;
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
   * 将输入转换为字符串。
   *
   * @param value 待处理值
   * @return 字符串值
   */
  private String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 规范化时间。
   *
   * @param item 数据项
   * @param key 键
   */
  private void normalizeTime(Map<String, Object> item, String key) {
    if (item == null) return;
    Object value = item.get(key);
    if (value instanceof Timestamp) item.put(key, ((Timestamp) value).toInstant());
  }
}
