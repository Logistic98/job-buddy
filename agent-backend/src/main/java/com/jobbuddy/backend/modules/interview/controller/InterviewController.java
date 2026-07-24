package com.jobbuddy.backend.modules.interview.controller;

import com.jobbuddy.backend.common.dto.response.QuestionIdResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewCodeRunRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamSubmitRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewGenerateRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewImportRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewQuestionRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewBatchResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewCodeRunResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewExamResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewExamSubmitResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewGenerateResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewImportResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionMetaResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionPageResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionResponse;
import com.jobbuddy.backend.modules.interview.service.InterviewDocumentTextExtractor;
import com.jobbuddy.backend.modules.interview.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 面试题库接口，提供题目管理、题目生成、考试创建和考试提交能力。 */
@Tag(name = "面试题库接口")
@RestController
@RequirePermission(PermissionCodes.PRACTICE_USE)
@RequestMapping("/api/interview")
public class InterviewController {
  private final InterviewService interviewService;
  private final InterviewDocumentTextExtractor documentTextExtractor;

  public InterviewController(
      InterviewService interviewService, InterviewDocumentTextExtractor documentTextExtractor) {
    this.interviewService = interviewService;
    this.documentTextExtractor = documentTextExtractor;
  }

  /**
   * 分页查询面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "分页查询面试题")
  @GetMapping("/questions")
  public ApiResponse<InterviewQuestionPageResponse> questions(
      @RequestParam(value = "keyword", required = false) String keyword,
      @RequestParam(value = "bankType", required = false) String bankType,
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "difficulty", required = false) String difficulty,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "size", required = false) Integer size) {
    return ApiResponse.success(
        interviewService.pageQuestions(keyword, bankType, category, difficulty, page, size));
  }

  /**
   * 查询题库元数据。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询题库元数据")
  @GetMapping("/questions/meta")
  public ApiResponse<InterviewQuestionMetaResponse> questionMeta(
      @RequestParam(value = "bankType", required = false) String bankType) {
    return ApiResponse.success(interviewService.questionMeta(bankType));
  }

  /**
   * 创建面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "创建面试题")
  @PostMapping("/questions")
  public ApiResponse<InterviewQuestionResponse> createQuestion(
      @RequestBody InterviewQuestionRequest payload) {
    return ApiResponse.success(interviewService.saveQuestion(payload, null));
  }

  /**
   * 批量导入面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "批量导入面试题")
  @PostMapping("/questions/import")
  public ApiResponse<InterviewImportResponse> importQuestions(
      @RequestBody InterviewImportRequest payload) {
    return ApiResponse.success(interviewService.importQuestions(payload));
  }

  /**
   * 提取 AI 出题参考资料中的纯文本。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "提取 AI 出题参考资料文本")
  @PostMapping(value = "/documents/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<InterviewDocumentExtractResponse> extractDocumentText(
      @RequestParam("file") MultipartFile file) {
    return ApiResponse.success(documentTextExtractor.extract(file));
  }

  /**
   * 生成面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "生成面试题")
  @PostMapping("/questions/generate")
  public ApiResponse<InterviewGenerateResponse> generateQuestions(
      @RequestBody InterviewGenerateRequest payload) {
    return ApiResponse.success(interviewService.generateQuestions(payload));
  }

  /**
   * 批量处理面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "批量处理面试题")
  @PostMapping("/questions/batch")
  public ApiResponse<InterviewBatchResponse> batchQuestions(
      @RequestBody InterviewBatchRequest payload) {
    return ApiResponse.success(interviewService.batchQuestions(payload));
  }

  /**
   * 更新面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "更新面试题")
  @PutMapping("/questions/{questionId}")
  public ApiResponse<InterviewQuestionResponse> updateQuestion(
      @PathVariable String questionId, @RequestBody InterviewQuestionRequest payload) {
    return ApiResponse.success(interviewService.saveQuestion(payload, questionId));
  }

  /**
   * 删除面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除面试题")
  @DeleteMapping("/questions/{questionId}")
  public ApiResponse<QuestionIdResponse> deleteQuestion(@PathVariable String questionId) {
    interviewService.deleteQuestion(questionId);
    return ApiResponse.success(new QuestionIdResponse(questionId));
  }

  /**
   * 运行编程题样例。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "运行编程题样例")
  @PostMapping("/code/run")
  public ApiResponse<InterviewCodeRunResponse> runCode(
      @RequestBody InterviewCodeRunRequest payload) {
    return ApiResponse.success(interviewService.runCode(payload));
  }

  /**
   * 查询考试列表。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询考试列表")
  @GetMapping("/practices")
  public ApiResponse<List<InterviewExamResponse>> exams(HttpServletRequest request) {
    return ApiResponse.success(
        interviewService.listExams(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.userId(request)));
  }

  /**
   * 创建随机考试。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "创建随机考试")
  @PostMapping("/practices/random")
  public ApiResponse<InterviewExamResponse> randomExam(
      @RequestBody InterviewExamRequest payload, HttpServletRequest request) {
    return ApiResponse.success(
        interviewService.createRandomExam(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            payload));
  }

  /**
   * 查询考试详情。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询考试详情")
  @GetMapping("/practices/{examId}")
  public ApiResponse<InterviewExamResponse> exam(
      @PathVariable String examId, HttpServletRequest request) {
    return ApiResponse.success(
        interviewService.getExam(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            examId));
  }

  /**
   * 提交考试答案。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "提交考试答案")
  @PostMapping("/practices/{examId}/submit")
  public ApiResponse<InterviewExamSubmitResponse> submitExam(
      @PathVariable String examId,
      @RequestBody InterviewExamSubmitRequest payload,
      HttpServletRequest request) {
    return ApiResponse.success(
        interviewService.submitExam(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            examId,
            payload));
  }
}
