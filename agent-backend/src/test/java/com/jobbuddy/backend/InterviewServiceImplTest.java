package com.jobbuddy.backend;

import com.jobbuddy.backend.modules.interview.repository.InterviewRepository;
import com.jobbuddy.backend.modules.interview.service.impl.InterviewCodeRunner;
import com.jobbuddy.backend.modules.interview.service.impl.InterviewServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewServiceImplTest {

    private final InterviewRepository repository = mock(InterviewRepository.class);
    private final InterviewCodeRunner codeRunner = mock(InterviewCodeRunner.class);
    private final InterviewServiceImpl service = new InterviewServiceImpl(repository, codeRunner);

    @Test
    void pageQuestionsShouldClampPageAndSizeAndComputePages() {
        when(repository.countQuestions(null, null, null, null)).thenReturn(45);
        when(repository.listQuestions(null, null, null, null, 1, 100))
                .thenReturn(Collections.<Map<String, Object>>emptyList());

        Map<String, Object> result = service.pageQuestions(null, null, null, null, Integer.valueOf(0), Integer.valueOf(500));

        assertEquals(Integer.valueOf(1), result.get("page"));
        assertEquals(Integer.valueOf(100), result.get("size"));
        assertEquals(Integer.valueOf(45), result.get("total"));
        assertEquals(Integer.valueOf(1), result.get("pages"));
    }

    @Test
    void pageQuestionsShouldUseDefaultsAndCeilTotalPages() {
        when(repository.countQuestions(null, null, null, null)).thenReturn(41);
        when(repository.listQuestions(null, null, null, null, 1, 20))
                .thenReturn(Collections.<Map<String, Object>>emptyList());

        Map<String, Object> result = service.pageQuestions(null, null, null, null, null, null);

        assertEquals(Integer.valueOf(20), result.get("size"));
        assertEquals(Integer.valueOf(3), result.get("pages"));
    }

    @Test
    void saveQuestionShouldRejectMissingTitle() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("content", "内容");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                service.saveQuestion(payload, null);
            }
        });
        assertEquals("题目标题不能为空", error.getMessage());
    }

    @Test
    void saveQuestionShouldNormalizeCodingBankTypeAndForceQuestionType() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", "两数之和");
        payload.put("content", "实现 twoSum");
        payload.put("bankType", "编程题库");
        when(repository.findQuestion(anyString())).thenReturn(new LinkedHashMap<String, Object>());

        service.saveQuestion(payload, null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(repository).saveQuestion(captor.capture());
        Map<String, Object> saved = captor.getValue();
        assertEquals("leetcode", saved.get("bankType"));
        assertEquals("编程题", saved.get("questionType"));
        assertEquals("中等", saved.get("difficulty"));
        assertEquals("通用", saved.get("category"));
        assertTrue(String.valueOf(saved.get("questionId")).startsWith("iq_"));
    }

    @Test
    void batchQuestionsShouldRejectEmptyIdsAndEmptyFields() {
        Map<String, Object> noIds = new LinkedHashMap<String, Object>();
        noIds.put("questionIds", Collections.emptyList());
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                service.batchQuestions(noIds);
            }
        });

        Map<String, Object> noFields = new LinkedHashMap<String, Object>();
        noFields.put("questionIds", Arrays.asList("q1"));
        noFields.put("action", "update");
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                service.batchQuestions(noFields);
            }
        });
    }

    @Test
    void batchDeleteShouldFilterBlankIdsAndReportCount() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("questionIds", Arrays.asList("q1", "  ", null, "q2"));
        payload.put("action", "delete");

        Map<String, Object> result = service.batchQuestions(payload);

        verify(repository).batchDeleteQuestions(eq(Arrays.asList("q1", "q2")));
        assertEquals(Integer.valueOf(2), result.get("count"));
        assertEquals("delete", result.get("action"));
    }

    @Test
    void submitExamShouldScoreChoiceAndCodingQuestions() {
        Map<String, Object> exam = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
        questions.add(question("q1", "单选", "A"));
        questions.add(question("q2", "编程题", null));
        exam.put("questions", questions);
        when(repository.findExam("e1")).thenReturn(exam);

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("q1", "A");
        answers.put("q2", "print(1)");
        Map<String, Object> codingResults = new LinkedHashMap<String, Object>();
        Map<String, Object> passed = new LinkedHashMap<String, Object>();
        passed.put("passed", Boolean.TRUE);
        codingResults.put("q2", passed);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("answers", answers);
        payload.put("codingResults", codingResults);

        service.submitExam("e1", payload);

        verify(repository).saveExamAnswer(eq("e1"), eq("q1"), eq("A"), eq(true), anyDouble());
        verify(repository).saveExamAnswer(eq("e1"), eq("q2"), eq("print(1)"), eq(true), anyDouble());
        verify(repository).finishExam(eq("e1"), eq(2), eq(100.0));
    }

    @Test
    void submitExamShouldScoreZeroForWrongAndMissingCodingResult() {
        Map<String, Object> exam = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
        questions.add(question("q1", "单选", "A"));
        questions.add(question("q2", "编程题", null));
        exam.put("questions", questions);
        when(repository.findExam("e1")).thenReturn(exam);

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("q1", "B");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("answers", answers);

        service.submitExam("e1", payload);

        verify(repository).saveExamAnswer(eq("e1"), eq("q1"), eq("B"), eq(false), anyDouble());
        verify(repository).saveExamAnswer(eq("e1"), eq("q2"), eq((String) null), eq(false), anyDouble());
        verify(repository).finishExam(eq("e1"), eq(1), eq(0.0));
    }

    @Test
    void submitExamShouldPassShortAnswerWhenKeySegmentsCovered() {
        Map<String, Object> exam = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
        questions.add(question("q1", "简答", "线程安全；可见性；有序性"));
        exam.put("questions", questions);
        when(repository.findExam("e1")).thenReturn(exam);

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("q1", "volatile 保证可见性和有序性");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("answers", answers);

        service.submitExam("e1", payload);

        verify(repository).saveExamAnswer(eq("e1"), eq("q1"), anyString(), eq(true), anyDouble());
        verify(repository).finishExam(eq("e1"), anyInt(), anyDouble());
    }

    @Test
    void getExamShouldFailFastWhenExamMissing() {
        when(repository.findExam("missing")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                service.getExam("missing");
            }
        });
    }

    private Map<String, Object> question(String id, String questionType, String answer) {
        Map<String, Object> question = new LinkedHashMap<String, Object>();
        question.put("questionId", id);
        question.put("questionType", questionType);
        question.put("answer", answer);
        question.put("bankType", "编程题".equals(questionType) ? "leetcode" : "baguwen");
        return question;
    }
}
