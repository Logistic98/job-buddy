package com.jobbuddy.backend.modules.project.service.impl;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionGenerateRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionImportRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectQuestionResponse;
import com.jobbuddy.backend.modules.project.dto.response.ProjectResponse;
import com.jobbuddy.backend.modules.project.repository.ProjectDeepDiveRepository;
import com.jobbuddy.backend.modules.project.service.ProjectDeepDiveService;
import com.jobbuddy.backend.modules.project.service.ProjectMaterialFile;
import com.jobbuddy.backend.modules.project.storage.ProjectMaterialStorage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 协调项目事务、私有材料存储与 Runtime 辅助出题。
 *
 * <p>数据库元数据只围绕已校验的存储操作提交，生成题与手工题遵循同一属主边界。
 */
@Service
public class ProjectDeepDiveServiceImpl implements ProjectDeepDiveService {
  static final long MAX_MATERIAL_SIZE_BYTES = 1024L * 1024L * 1024L;

  private final ProjectDeepDiveRepository repository;
  private final ProjectMaterialStorage materialStorage;
  private final JsonCodec jsonCodec;

  /**
   * 创建项目深度分析服务实例。
   *
   * @param repository 存储访问
   * @param materialStorage 材料存储
   * @param jsonCodec JSON 编解码器
   */
  @Autowired
  public ProjectDeepDiveServiceImpl(
      ProjectDeepDiveRepository repository,
      ProjectMaterialStorage materialStorage,
      JsonCodec jsonCodec) {
    this.repository = repository;
    this.materialStorage = materialStorage;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 创建项目深度分析服务实例。
   *
   * @param repository 存储访问
   * @param materialStorage 材料存储
   */
  public ProjectDeepDiveServiceImpl(
      ProjectDeepDiveRepository repository, ProjectMaterialStorage materialStorage) {
    this(repository, materialStorage, new JsonCodec());
  }

  /**
   * 查询项目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 项目列表
   */
  public List<ProjectResponse> listProjects(String tenantId, String userId) {
    return jsonCodec.convertList(repository.listProjects(tenantId, userId), ProjectResponse.class);
  }

  /**
   * 获取项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 项目
   */
  public ProjectResponse getProject(String tenantId, String userId, String projectId) {
    return projectResponse(requireProject(tenantId, userId, projectId));
  }

  /**
   * 保存项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @param projectId 项目标识
   * @return 保存后的项目
   */
  public ProjectResponse saveProject(
      String tenantId, String userId, ProjectRequest request, String projectId) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Map<String, Object> project = new LinkedHashMap<String, Object>();
    project.put("tenantId", tenantId);
    project.put("userId", userId);
    project.put(
        "projectId",
        projectId == null || projectId.trim().isEmpty() ? "pdd_" + randomId() : projectId);
    project.put("name", required(payload, "name", "项目名称不能为空"));
    project.put("role", defaultString(payload.get("role"), "核心开发"));
    project.put("summary", defaultString(payload.get("summary"), ""));
    project.put("techStack", defaultString(payload.get("techStack"), ""));
    project.put("projectPeriod", defaultString(payload.get("projectPeriod"), ""));
    project.put("teamSize", defaultString(payload.get("teamSize"), ""));
    project.put("projectType", defaultString(payload.get("projectType"), ""));
    project.put("businessDomain", defaultString(payload.get("businessDomain"), ""));
    project.put("projectStatus", defaultString(payload.get("projectStatus"), ""));
    project.put("background", defaultString(payload.get("background"), ""));
    project.put("responsibilities", defaultString(payload.get("responsibilities"), ""));
    project.put("highlights", defaultString(payload.get("highlights"), ""));
    project.put("challenges", defaultString(payload.get("challenges"), ""));
    project.put("outcomes", defaultString(payload.get("outcomes"), ""));
    repository.saveProject(project);
    return projectResponse(
        repository.findProject(tenantId, userId, String.valueOf(project.get("projectId"))));
  }

  /**
   * 删除项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   */
  public void deleteProject(String tenantId, String userId, String projectId) {
    repository.deleteProject(tenantId, userId, projectId);
  }

  /**
   * 新增材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param file 上传文件
   * @return 新增后的材料
   * @throws IOException 文件或网络读写失败时抛出
   */
  public ProjectResponse addMaterial(
      String tenantId, String userId, String projectId, MultipartFile file) throws IOException {
    if (repository.findProject(tenantId, userId, projectId) == null)
      throw new IllegalArgumentException("项目不存在");
    validateMaterialFile(file);
    String sha256 = sha256(file);
    if (repository.findMaterialBySha256(tenantId, userId, projectId, sha256) != null) {
      return projectResponse(repository.findProject(tenantId, userId, projectId));
    }

    String materialId = "mat_" + randomId();
    String fileName = safeFileName(file.getOriginalFilename());
    String contentType = safeContentType(file.getContentType());
    String storagePath = "project-materials/" + projectId + "/" + materialId;
    materialStorage.upload(file, storagePath, contentType);

    Map<String, Object> material = new LinkedHashMap<String, Object>();
    material.put("materialId", materialId);
    material.put("projectId", projectId);
    material.put("fileName", fileName);
    material.put("contentType", contentType);
    material.put("storagePath", storagePath);
    material.put("sizeBytes", Long.valueOf(file.getSize()));
    material.put("sha256", sha256);
    try {
      repository.saveMaterial(tenantId, userId, material);
    } catch (RuntimeException e) {
      try {
        materialStorage.delete(storagePath);
      } catch (RuntimeException cleanupError) {
        e.addSuppressed(cleanupError);
      }
      throw e;
    }
    return projectResponse(repository.findProject(tenantId, userId, projectId));
  }

  /**
   * 打开材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   * @return 可读取的材料
   */
  public ProjectMaterialFile openMaterial(String tenantId, String userId, String materialId) {
    Map<String, Object> material = requireMaterial(tenantId, userId, materialId);
    String fileName = defaultString(material.get("fileName"), "项目文件");
    String contentType = safeContentType(stringValue(material.get("contentType")));
    String storagePath = stringValue(material.get("storagePath"));
    if (storagePath == null || storagePath.isBlank()) {
      throw new IllegalStateException("项目材料缺少对象存储路径");
    }
    return new ProjectMaterialFile(
        fileName,
        contentType,
        longValue(material.get("sizeBytes"), 0L),
        materialStorage.open(storagePath));
  }

  /**
   * 删除材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   */
  public void deleteMaterial(String tenantId, String userId, String materialId) {
    Map<String, Object> material = requireMaterial(tenantId, userId, materialId);
    String storagePath = stringValue(material.get("storagePath"));
    if (storagePath != null && !storagePath.isBlank()) materialStorage.delete(storagePath);
    repository.deleteMaterial(tenantId, userId, materialId);
  }

  /**
   * 生成题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param request 请求对象
   * @return 题目
   */
  public List<ProjectQuestionResponse> generateQuestions(
      String tenantId, String userId, String projectId, ProjectQuestionGenerateRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Map<String, Object> project = repository.findProject(tenantId, userId, projectId);
    if (project == null) throw new IllegalArgumentException("项目不存在");
    int count = intValue(payload == null ? null : payload.get("count"), 12);
    count = Math.max(1, Math.min(count, 100));
    String focus =
        defaultString(payload == null ? null : payload.get("focus"), "架构设计、技术难点、项目结果、排障复盘");
    String requirements =
        limitedString(
            payload == null ? null : payload.get("requirements"), 1000, "生成要求不能超过 1000 个字符");
    String materialText = projectEvidenceText(project);
    List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
    String[] categories = new String[] {"项目背景", "架构设计", "技术难点", "业务价值", "性能稳定性", "协作复盘"};
    for (int i = 0; i < count; i++) {
      String category = categories[i % categories.length];
      questions.add(question(project, category, focus, requirements, materialText, i + 1));
    }
    return jsonCodec.convertList(questions, ProjectQuestionResponse.class);
  }

  /**
   * 导入题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param request 请求对象
   * @return 导入后的题目列表
   */
  @Transactional
  public ProjectResponse importQuestions(
      String tenantId, String userId, String projectId, ProjectQuestionImportRequest request) {
    if (repository.findProject(tenantId, userId, projectId) == null)
      throw new IllegalArgumentException("项目不存在");
    List<ProjectQuestionRequest> candidates = request == null ? null : request.getQuestions();
    if (candidates == null || candidates.isEmpty()) throw new IllegalArgumentException("请至少选择一道问题");
    if (candidates.size() > 100) throw new IllegalArgumentException("单次最多添加 100 道问题");
    for (ProjectQuestionRequest candidate : candidates) {
      Map<String, Object> payload = jsonCodec.toMap(candidate);
      Map<String, Object> question = new LinkedHashMap<String, Object>();
      question.put("questionId", "pdq_" + randomId());
      question.put("projectId", projectId);
      question.put("question", required(payload, "question", "问题内容不能为空"));
      question.put("answer", defaultString(payload.get("answer"), ""));
      question.put("category", defaultString(payload.get("category"), "自定义"));
      question.put("difficulty", defaultString(payload.get("difficulty"), "中等"));
      question.put("source", "generated");
      repository.saveQuestion(tenantId, userId, question);
    }
    return projectResponse(repository.findProject(tenantId, userId, projectId));
  }

  /**
   * 新增题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param request 请求对象
   * @return 新增后的题目
   */
  public ProjectResponse addQuestion(
      String tenantId, String userId, String projectId, ProjectQuestionRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    if (repository.findProject(tenantId, userId, projectId) == null)
      throw new IllegalArgumentException("项目不存在");
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put("questionId", "pdq_" + randomId());
    question.put("projectId", projectId);
    question.put("question", required(payload, "question", "问题内容不能为空"));
    question.put("answer", defaultString(payload.get("answer"), ""));
    question.put("category", defaultString(payload.get("category"), "自定义"));
    question.put("difficulty", defaultString(payload.get("difficulty"), "中等"));
    question.put("source", "manual");
    repository.saveQuestion(tenantId, userId, question);
    return projectResponse(repository.findProject(tenantId, userId, projectId));
  }

  /**
   * 更新题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   * @param request 请求对象
   * @return 更新后的题目
   */
  public ProjectResponse updateQuestion(
      String tenantId, String userId, String questionId, ProjectQuestionRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Map<String, Object> existing = repository.findQuestion(tenantId, userId, questionId);
    if (existing == null) throw new IllegalArgumentException("问题不存在");
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put("questionId", questionId);
    question.put("question", required(payload, "question", "问题内容不能为空"));
    question.put("answer", defaultString(payload.get("answer"), ""));
    question.put(
        "category",
        defaultString(payload.get("category"), defaultString(existing.get("category"), "自定义")));
    question.put(
        "difficulty",
        defaultString(payload.get("difficulty"), defaultString(existing.get("difficulty"), "中等")));
    // 用户编辑过的问题归为 manual，重新生成时不会被替换
    question.put("source", "manual");
    repository.updateQuestion(
        tenantId, userId, String.valueOf(existing.get("projectId")), question);
    return projectResponse(
        repository.findProject(tenantId, userId, String.valueOf(existing.get("projectId"))));
  }

  /**
   * 删除题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   */
  public void deleteQuestion(String tenantId, String userId, String questionId) {
    repository.deleteQuestion(tenantId, userId, questionId);
  }

  /**
   * 获取题目。
   *
   * @param project 项目
   * @param category 题目分类
   * @param focus 考查重点
   * @param requirements 生成要求
   * @param materialText 材料文本
   * @param index 索引
   * @return 题目
   */
  private Map<String, Object> question(
      Map<String, Object> project,
      String category,
      String focus,
      String requirements,
      String materialText,
      int index) {
    String name = String.valueOf(project.get("name"));
    String role = defaultString(project.get("role"), "核心开发");
    String tech = defaultString(project.get("techStack"), "项目相关技术栈");
    String evidence = evidence(materialText, index);
    String question;
    String answer;
    if ("项目背景".equals(category)) {
      question = "你为什么要做“" + name + "”这个项目？它解决了什么真实问题，你负责的边界是什么？";
      answer =
          "可以先说明业务背景和痛点，再说明项目目标，最后落到个人职责。回答重点：我在项目中担任"
              + role
              + "，围绕"
              + focus
              + "推进落地；可结合材料说明："
              + evidence;
    } else if ("架构设计".equals(category)) {
      question = "“" + name + "”的整体架构是怎样的？核心模块之间如何协作？";
      answer =
          "建议按入口层、业务层、数据层、异步任务或外部依赖说明。突出为什么这样拆分、如何保证扩展性和可维护性。技术栈可提到：" + tech + "。材料依据：" + evidence;
    } else if ("技术难点".equals(category)) {
      question = "项目中最难的技术点是什么？你是如何定位、拆解和解决的？";
      answer = "回答可采用“问题现象-原因分析-方案对比-最终实现-结果验证”的结构，并突出个人贡献。材料依据：" + evidence;
    } else if ("业务价值".equals(category)) {
      question = "这个项目最终产生了什么价值？有没有量化指标或可观察结果？";
      answer = "优先给出效率、稳定性、成本、体验或交付周期方面的指标；没有精确数字时，可以描述对流程和协作的改善。材料依据：" + evidence;
    } else if ("性能稳定性".equals(category)) {
      question = "如果该项目线上出现性能下降或异常，你会如何排查？";
      answer = "建议从日志、指标、链路追踪、数据库慢查询、缓存命中率、外部依赖超时等角度说明，并给出回滚、降级和验证方式。材料依据：" + evidence;
    } else {
      question = "复盘这个项目，你觉得还有哪些不足？如果重做会如何优化？";
      answer = "可以从架构边界、测试覆盖、可观测性、数据模型、自动化交付和团队协作角度复盘。重点体现成长和工程判断。材料依据：" + evidence;
    }
    if (!requirements.isBlank()) answer = answer + "\n回答约束：" + requirements;
    Map<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("question", question);
    item.put("answer", answer);
    item.put("category", category);
    item.put("difficulty", new String[] {"简单", "中等", "困难"}[(index - 1) % 3]);
    item.put("source", "generated");
    return item;
  }

  /**
   * 获取项目证据文本。
   *
   * @param project 项目
   * @return 项目证据文本
   */
  private String projectEvidenceText(Map<String, Object> project) {
    String text =
        String.join(
            "\n",
            defaultString(project.get("summary"), ""),
            defaultString(project.get("background"), ""),
            defaultString(project.get("responsibilities"), ""),
            defaultString(project.get("highlights"), ""),
            defaultString(project.get("challenges"), ""),
            defaultString(project.get("outcomes"), ""));
    return text.trim().isEmpty() ? "项目档案信息不足，请结合实际经历补充回答。" : text;
  }

  /**
   * 获取证据。
   *
   * @param text 文本
   * @param index 索引
   * @return 证据
   */
  private String evidence(String text, int index) {
    String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    if (clean.isEmpty()) return "暂无明确材料，需要结合实际经历补充。";
    int start = Math.min(Math.max(0, (index - 1) * 120), Math.max(0, clean.length() - 1));
    return clean.substring(start, Math.min(clean.length(), start + 220));
  }

  /**
   * 校验并获取项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 校验后的并获取项目
   */
  private Map<String, Object> requireProject(String tenantId, String userId, String projectId) {
    Map<String, Object> project = repository.findProject(tenantId, userId, projectId);
    if (project == null) throw new IllegalArgumentException("项目不存在");
    return project;
  }

  /**
   * 获取项目响应。
   *
   * @param project 项目
   * @return 项目响应
   */
  private ProjectResponse projectResponse(Map<String, Object> project) {
    return jsonCodec.convert(project, ProjectResponse.class);
  }

  /**
   * 校验并获取必填值。
   *
   * @param payload 请求载荷
   * @param key 业务键
   * @param message 消息内容
   * @return 必填配置值
   */
  private String required(Map<String, Object> payload, String key, String message) {
    String value = payload == null ? null : stringValue(payload.get(key));
    if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
    return value.trim();
  }

  /**
   * 获取默认字符串。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 默认字符串
   */
  private String defaultString(Object value, String fallback) {
    String text = stringValue(value);
    return text == null || text.trim().isEmpty() ? fallback : text.trim();
  }

  /**
   * 截取限定长度的字符串。
   *
   * @param value 输入值
   * @param maxLength 最大长度
   * @param message 消息内容
   * @return 限长后的字符串
   */
  private String limitedString(Object value, int maxLength, String message) {
    String text = defaultString(value, "");
    if (text.length() > maxLength) throw new IllegalArgumentException(message);
    return text;
  }

  /**
   * 校验并获取材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   * @return 校验后的并获取材料
   */
  private Map<String, Object> requireMaterial(String tenantId, String userId, String materialId) {
    Map<String, Object> material = repository.findMaterial(tenantId, userId, materialId);
    if (material == null) throw new IllegalArgumentException("项目文件不存在");
    return material;
  }

  /**
   * 校验材料文件。
   *
   * @param file 上传文件
   */
  private void validateMaterialFile(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() <= 0)
      throw new IllegalArgumentException("项目文件不能为空");
    if (file.getSize() > MAX_MATERIAL_SIZE_BYTES)
      throw new IllegalArgumentException("单个项目文件不能超过 1GB");
  }

  /**
   * 获取安全数据文件名称。
   *
   * @param originalFilename 原始文件名
   * @return 安全数据文件名称
   */
  private String safeFileName(String originalFilename) {
    String cleanName =
        StringUtils.cleanPath(originalFilename == null ? "" : originalFilename).trim();
    if (cleanName.isEmpty() || cleanName.contains(".."))
      throw new IllegalArgumentException("项目文件名不合法");
    return cleanName.length() <= 512 ? cleanName : cleanName.substring(cleanName.length() - 512);
  }

  /**
   * 获取安全数据内容类型。
   *
   * @param value 输入值
   * @return 安全数据内容类型
   */
  private String safeContentType(String value) {
    String contentType =
        value == null || value.isBlank() ? "application/octet-stream" : value.trim();
    return contentType.length() <= 128 ? contentType : contentType.substring(0, 128);
  }

  /**
   * 计算 SHA-256 摘要。
   *
   * @param file 上传文件
   * @return SHA-256 摘要
   * @throws IOException 文件或网络读写失败时抛出
   */
  private String sha256(MultipartFile file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      try (InputStream input = file.getInputStream()) {
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read > 0) digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
    }
  }

  /**
   * 获取字符串值。
   *
   * @param value 输入值
   * @return 字符串值
   */
  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 获取整数值。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 整数值
   */
  private int intValue(Object value, int fallback) {
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    } catch (Exception e) {
      return fallback;
    }
  }

  /**
   * 获取长整型值。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 长整型值
   */
  private long longValue(Object value, long fallback) {
    try {
      return value == null ? fallback : Long.parseLong(String.valueOf(value));
    } catch (Exception e) {
      return fallback;
    }
  }

  /**
   * 随机生成标识。
   *
   * @return 随机标识
   */
  private String randomId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
