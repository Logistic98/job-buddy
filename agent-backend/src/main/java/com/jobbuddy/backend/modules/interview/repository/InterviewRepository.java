package com.jobbuddy.backend.modules.interview.repository;

import java.sql.Clob;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.interview.mapper.InterviewMapper;

/**
 * Repository adapter for interview questions and mock exams.
 *
 * <p>The mapper works with database-oriented fields such as {@code tagsJson}; this class normalizes
 * those fields into API-friendly structures before returning data to services.</p>
 */
@Repository
public class InterviewRepository {
    private static final Pattern TAG_LABEL_PATTERN = Pattern.compile(
            "(?:^|[\\{,\\s])label\\s*[:=]\\s*([^,}\\]]+)",
            Pattern.CASE_INSENSITIVE);

    private final InterviewMapper mapper;
    private final JsonCodec jsonCodec;

    public InterviewRepository(InterviewMapper mapper, JsonCodec jsonCodec) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
    }

    public List<Map<String, Object>> listQuestions(String keyword, String category) {
        return listQuestions(keyword, null, category, null, 1, 200);
    }

    public List<Map<String, Object>> listQuestions(String keyword, String bankType, String category, int page, int size) {
        return listQuestions(keyword, bankType, category, null, page, size);
    }

    public List<Map<String, Object>> listQuestions(
            String keyword,
            String bankType,
            String category,
            String difficulty,
            int page,
            int size) {
        int normalizedSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page - 1) * normalizedSize;
        List<Map<String, Object>> rows = mapper.listQuestions(
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

    public int countQuestions(String keyword, String category) {
        return countQuestions(keyword, null, category, null);
    }

    public int countQuestions(String keyword, String bankType, String category, String difficulty) {
        return mapper.countQuestions(like(keyword), trim(bankType), trim(category), trim(difficulty));
    }

    public List<Map<String, Object>> findEnabled(String category, String difficulty) {
        return findEnabled(null, category, difficulty, null);
    }

    public List<Map<String, Object>> findEnabled(String bankType, String category, String difficulty, String questionType) {
        List<Map<String, Object>> rows = mapper.findEnabled(
                trim(bankType),
                trim(category),
                trim(difficulty),
                trim(questionType));
        for (Map<String, Object> row : rows) {
            hydrateQuestion(row);
        }
        return rows;
    }

    public Map<String, Object> questionMeta(String bankType) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("bankTypes", mapper.listBankTypes());
        meta.put("categories", mapper.listCategories(trim(bankType)));
        meta.put("difficulties", mapper.listDifficulties(trim(bankType)));
        meta.put("questionTypes", mapper.listQuestionTypes(trim(bankType)));
        return meta;
    }

    public Map<String, Object> findQuestion(String questionId) {
        return hydrateQuestion(mapper.findQuestion(questionId));
    }

    public void saveQuestion(Map<String, Object> question) {
        question.put("tagsJson", jsonCodec.toJson(question.get("tags")));
        question.put("codingMetaJson", jsonCodec.toJson(question.get("codingMeta")));
        question.put("enabled", Boolean.valueOf(!Boolean.FALSE.equals(question.get("enabled"))));
        question.put("updatedAt", Timestamp.from(Instant.now()));

        if (mapper.countQuestion(question.get("questionId")) > 0) {
            mapper.updateQuestion(question);
        } else {
            mapper.insertQuestion(question);
        }
    }

    public void deleteQuestion(String questionId) {
        mapper.softDeleteQuestion(questionId, Timestamp.from(Instant.now()));
    }

    public void batchDeleteQuestions(List<String> questionIds) {
        Timestamp now = Timestamp.from(Instant.now());
        for (String questionId : questionIds) {
            mapper.softDeleteQuestion(questionId, now);
        }
    }

    public void batchUpdateQuestions(List<String> questionIds, Map<String, Object> fields) {
        Timestamp now = Timestamp.from(Instant.now());
        for (String questionId : questionIds) {
            if (fields.containsKey("category")) {
                mapper.updateQuestionCategory(questionId, fields.get("category"), now);
            }
            if (fields.containsKey("difficulty")) {
                mapper.updateQuestionDifficulty(questionId, fields.get("difficulty"), now);
            }
            if (fields.containsKey("tags")) {
                mapper.updateQuestionTags(questionId, jsonCodec.toJson(fields.get("tags")), now);
            }
        }
    }

    public void createExam(String examId, String title, int durationMinutes, Object strategy, List<Map<String, Object>> questions) {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expiresAt = Timestamp.from(now.toInstant().plus(Duration.ofMinutes(Math.max(1, durationMinutes))));
        mapper.insertExam(examId, title, "running", questions.size(), 0, null, durationMinutes, jsonCodec.toJson(strategy), now, expiresAt);

        int order = 1;
        for (Map<String, Object> question : questions) {
            mapper.insertExamQuestion(examId, question.get("questionId"), order++);
        }
    }

    public Map<String, Object> findExam(String examId) {
        Map<String, Object> exam = mapper.findExam(examId);
        if (exam == null) {
            return null;
        }

        hydrateExam(exam);
        exam.put("questions", examQuestions(examId));
        return exam;
    }

    public List<Map<String, Object>> listExams() {
        List<Map<String, Object>> rows = mapper.listExams();
        for (Map<String, Object> row : rows) {
            hydrateExam(row);
        }
        return rows;
    }

    public List<Map<String, Object>> examQuestions(String examId) {
        List<Map<String, Object>> rows = mapper.examQuestions(examId);
        for (Map<String, Object> row : rows) {
            hydrateQuestion(row);
        }
        return rows;
    }

    public void saveExamAnswer(
            String examId,
            String questionId,
            String answer,
            boolean correct,
            double score) {
        mapper.saveExamAnswer(
                examId,
                questionId,
                answer,
                correct,
                score,
                Timestamp.from(Instant.now()));
    }

    public void finishExam(String examId, int answeredCount, double score) {
        mapper.finishExam(examId, answeredCount, score, Timestamp.from(Instant.now()));
    }

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

    private String cleanTagText(String value) {
        if (value == null) {
            return "";
        }

        String text = value.trim();
        Matcher matcher = TAG_LABEL_PATTERN.matcher(text);
        if (matcher.find()) {
            text = matcher.group(1).trim();
        }
        return text
                .replaceAll("^[\\{\\[\\(]+|[\\}\\]\\)]+$", "")
                .replaceAll("(?i)^label\\s*[:=]\\s*", "")
                .replaceAll("^['\"]|['\"]$", "")
                .trim();
    }

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

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String like(String value) {
        return value == null || value.trim().isEmpty() ? null : "%" + value.trim().toLowerCase() + "%";
    }

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

    private void normalizeTime(Map<String, Object> item, String key) {
        Object value = item.get(key);
        if (value instanceof Timestamp) {
            item.put(key, ((Timestamp) value).toInstant());
        }
    }
}
