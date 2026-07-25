package com.jobbuddy.backend.modules.project.service;

import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionGenerateRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionImportRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectQuestionResponse;
import com.jobbuddy.backend.modules.project.dto.response.ProjectResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理租户和用户级项目、材料文件与项目面试题。
 *
 * <p>材料通过属主校验后才返回受控流描述，不向 Controller 或客户端暴露对象存储键。
 */
public interface ProjectDeepDiveService {
  /**
   * 查询项目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 项目列表
   */
  List<ProjectResponse> listProjects(String tenantId, String userId);

  /**
   * 获取项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 项目
   */
  ProjectResponse getProject(String tenantId, String userId, String projectId);

  /**
   * 保存项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @param projectId 项目标识
   * @return 保存后的项目
   */
  ProjectResponse saveProject(
      String tenantId, String userId, ProjectRequest request, String projectId);

  /**
   * 删除项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   */
  void deleteProject(String tenantId, String userId, String projectId);

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
  ProjectResponse addMaterial(String tenantId, String userId, String projectId, MultipartFile file)
      throws IOException;

  /**
   * 校验访问权限后，将所属材料解析为可流式读取的文件描述。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   * @return 校验后的访问权限后，将所属材料解析为可流式读取的文件描述
   */
  ProjectMaterialFile openMaterial(String tenantId, String userId, String materialId);

  /**
   * 删除材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   */
  void deleteMaterial(String tenantId, String userId, String materialId);

  /**
   * 生成题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param request 请求对象
   * @return 题目
   */
  List<ProjectQuestionResponse> generateQuestions(
      String tenantId, String userId, String projectId, ProjectQuestionGenerateRequest request);

  /**
   * 导入题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param request 请求对象
   * @return 导入后的题目列表
   */
  ProjectResponse importQuestions(
      String tenantId, String userId, String projectId, ProjectQuestionImportRequest request);

  /**
   * 新增题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param request 请求对象
   * @return 新增后的题目
   */
  ProjectResponse addQuestion(
      String tenantId, String userId, String projectId, ProjectQuestionRequest request);

  /**
   * 更新题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   * @param request 请求对象
   * @return 更新后的题目
   */
  ProjectResponse updateQuestion(
      String tenantId, String userId, String questionId, ProjectQuestionRequest request);

  /**
   * 删除题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   */
  void deleteQuestion(String tenantId, String userId, String questionId);
}
