package com.jobbuddy.backend.modules.interview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.response.NamedValueResponse;
import com.jobbuddy.backend.common.dto.MapBackedDto;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewBatchResponse;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewExamResponse;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamSubmitRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewExamSubmitResponse;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewGenerateRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewGenerateResponse;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewImportRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewImportResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionPageResponse;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewQuestionRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionResponse;
import com.jobbuddy.backend.modules.interview.service.InterviewService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试题库接口，提供题目管理、题目生成、考试创建和考试提交能力。
 */
@Tag(name = "面试题库接口")
@RestController
@RequestMapping("/api/interview")
public class InterviewController {
    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
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
        return ApiResponse.success(InterviewQuestionPageResponse.from(interviewService.pageQuestions(keyword, bankType, category, difficulty, page, size)));
    }

    /**
     * 查询题库元数据。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询题库元数据")
    @GetMapping("/questions/meta")
    public ApiResponse<MapBackedDto> questionMeta(@RequestParam(value = "bankType", required = false) String bankType) {
        return ApiResponse.success(new MapBackedDto(interviewService.questionMeta(bankType)));
    }

    /**
     * 创建面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "创建面试题")
    @PostMapping("/questions")
    public ApiResponse<InterviewQuestionResponse> createQuestion(@RequestBody InterviewQuestionRequest payload) {
        return ApiResponse.success(InterviewQuestionResponse.from(interviewService.saveQuestion(payload == null ? null : payload.toMap(), null)));
    }

    /**
     * 批量导入面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "批量导入面试题")
    @PostMapping("/questions/import")
    public ApiResponse<InterviewImportResponse> importQuestions(@RequestBody InterviewImportRequest payload) {
        return ApiResponse.success(InterviewImportResponse.from(interviewService.importQuestions(payload == null ? null : payload.toMap())));
    }

    /**
     * 生成面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "生成面试题")
    @PostMapping("/questions/generate")
    public ApiResponse<InterviewGenerateResponse> generateQuestions(@RequestBody InterviewGenerateRequest payload) {
        return ApiResponse.success(InterviewGenerateResponse.from(interviewService.generateQuestions(payload == null ? null : payload.toMap())));
    }

    /**
     * 批量处理面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "批量处理面试题")
    @PostMapping("/questions/batch")
    public ApiResponse<InterviewBatchResponse> batchQuestions(@RequestBody InterviewBatchRequest payload) {
        return ApiResponse.success(InterviewBatchResponse.from(interviewService.batchQuestions(payload == null ? null : payload.toMap())));
    }

    /**
     * 更新面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "更新面试题")
    @PutMapping("/questions/{questionId}")
    public ApiResponse<InterviewQuestionResponse> updateQuestion(@PathVariable String questionId, @RequestBody InterviewQuestionRequest payload) {
        return ApiResponse.success(InterviewQuestionResponse.from(interviewService.saveQuestion(payload == null ? null : payload.toMap(), questionId)));
    }

    /**
     * 删除面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除面试题")
    @DeleteMapping("/questions/{questionId}")
    public ApiResponse<NamedValueResponse> deleteQuestion(@PathVariable String questionId) {
        return deleteQuestionCompat(questionId);
    }

    /**
     * 兼容方式删除面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "兼容方式删除面试题")
    @PostMapping("/questions/{questionId}/delete")
    public ApiResponse<NamedValueResponse> deleteQuestionByPost(@PathVariable String questionId) {
        return deleteQuestionCompat(questionId);
    }

    private ApiResponse<NamedValueResponse> deleteQuestionCompat(String questionId) {
        interviewService.deleteQuestion(questionId);
        return ApiResponse.success(new NamedValueResponse("questionId", questionId));
    }

    /**
     * 运行编程题样例。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "运行编程题样例")
    @PostMapping("/code/run")
    public ApiResponse<MapBackedDto> runCode(@RequestBody MapBackedDto payload) {
        return ApiResponse.success(new MapBackedDto(interviewService.runCode(payload == null ? null : payload.toMap())));
    }

    /**
     * 查询考试列表。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询考试列表")
    @GetMapping({"/exams", "/practices"})
    public ApiResponse<List<InterviewExamResponse>> exams() {
        return ApiResponse.success(MapBackedDto.fromMapList(interviewService.listExams(), InterviewExamResponse::from));
    }

    /**
     * 创建随机考试。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "创建随机考试")
    @PostMapping({"/exams/random", "/practices/random"})
    public ApiResponse<InterviewExamResponse> randomExam(@RequestBody InterviewExamRequest payload) {
        return ApiResponse.success(InterviewExamResponse.from(interviewService.createRandomExam(payload == null ? null : payload.toMap())));
    }

    /**
     * 查询考试详情。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询考试详情")
    @GetMapping({"/exams/{examId}", "/practices/{examId}"})
    public ApiResponse<InterviewExamResponse> exam(@PathVariable String examId) {
        return ApiResponse.success(InterviewExamResponse.from(interviewService.getExam(examId)));
    }

    /**
     * 提交考试答案。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "提交考试答案")
    @PostMapping({"/exams/{examId}/submit", "/practices/{examId}/submit"})
    public ApiResponse<InterviewExamSubmitResponse> submitExam(@PathVariable String examId, @RequestBody InterviewExamSubmitRequest payload) {
        return ApiResponse.success(InterviewExamSubmitResponse.from(interviewService.submitExam(examId, payload == null ? null : payload.toMap())));
    }
}
