package com.jobbuddy.backend.modules.interview.service.impl;

import com.jobbuddy.backend.modules.interview.service.InterviewService;

import com.jobbuddy.backend.modules.interview.repository.InterviewRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InterviewServiceImpl implements InterviewService {
    private final InterviewRepository interviewRepository;
    private final InterviewCodeRunner interviewCodeRunner;

    public InterviewServiceImpl(InterviewRepository interviewRepository, InterviewCodeRunner interviewCodeRunner) {
        this.interviewRepository = interviewRepository;
        this.interviewCodeRunner = interviewCodeRunner;
    }

    public List<Map<String, Object>> listQuestions(String keyword, String category) {
        return interviewRepository.listQuestions(keyword, category);
    }

    public Map<String, Object> pageQuestions(String keyword, String bankType, String category, String difficulty, Integer pageValue, Integer sizeValue) {
        int page = pageValue == null ? 1 : Math.max(1, pageValue.intValue());
        int size = sizeValue == null ? 20 : Math.max(1, Math.min(sizeValue.intValue(), 100));
        String normalizedBankType = normalizeBankType(bankType, null);
        int total = interviewRepository.countQuestions(keyword, normalizedBankType, category, difficulty);
        List<Map<String, Object>> items = interviewRepository.listQuestions(keyword, normalizedBankType, category, difficulty, page, size);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", Integer.valueOf(total));
        result.put("page", Integer.valueOf(page));
        result.put("size", Integer.valueOf(size));
        result.put("pages", Integer.valueOf((int) Math.ceil(total / (double) size)));
        return result;
    }

    public Map<String, Object> questionMeta(String bankType) {
        Map<String, Object> meta = interviewRepository.questionMeta(normalizeBankType(bankType, null));
        List<Map<String, Object>> bankTypes = new ArrayList<Map<String, Object>>();
        addBankTypeMeta(bankTypes, "leetcode", "算法题库");
        addBankTypeMeta(bankTypes, "baguwen", "问答题库");
        meta.put("bankTypeOptions", bankTypes);
        return meta;
    }

    public Map<String, Object> saveQuestion(Map<String, Object> payload, String questionId) {
        if (payload == null) payload = Collections.emptyMap();
        Map<String, Object> question = new LinkedHashMap<String, Object>();
        question.put("questionId", questionId == null || questionId.trim().isEmpty() ? "iq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : questionId);
        question.put("title", required(payload, "title", "题目标题不能为空"));
        question.put("content", required(payload, "content", "题目内容不能为空"));
        String questionType = defaultString(payload.get("questionType"), "单选");
        String bankType = normalizeBankType(stringValue(payload.get("bankType")), "编程题".equals(questionType) ? "leetcode" : "baguwen");
        if ("leetcode".equals(bankType)) questionType = "编程题";
        question.put("bankType", bankType);
        question.put("answer", stringValue(payload.get("answer")));
        question.put("category", defaultString(payload.get("category"), "通用"));
        question.put("difficulty", defaultString(payload.get("difficulty"), "中等"));
        question.put("questionType", questionType);
        question.put("tags", normalizeTags(payload.get("tags")));
        question.put("codingMeta", normalizeCodingMeta(payload.get("codingMeta")));
        question.put("enabled", Boolean.TRUE);
        interviewRepository.saveQuestion(question);
        return interviewRepository.findQuestion(String.valueOf(question.get("questionId")));
    }

    public void deleteQuestion(String questionId) {
        interviewRepository.deleteQuestion(questionId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> batchQuestions(Map<String, Object> payload) {
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
            if (difficulty != null && !difficulty.trim().isEmpty()) fields.put("difficulty", difficulty.trim());
            if (tags != null) fields.put("tags", normalizeTags(tags));
            if (fields.isEmpty()) throw new IllegalArgumentException("请至少填写分类、难度或标签中的一项");
            interviewRepository.batchUpdateQuestions(questionIds, fields);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(questionIds.size()));
        result.put("action", action);
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> importQuestions(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("items");
        List<Object> rows = value instanceof List ? (List<Object>) value : Collections.emptyList();
        int saved = 0;
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Object row : rows) {
            if (!(row instanceof Map)) continue;
            Map<String, Object> item = saveQuestion((Map<String, Object>) row, null);
            items.add(item);
            saved++;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(saved));
        result.put("items", items);
        return result;
    }

    public Map<String, Object> generateQuestions(Map<String, Object> payload) {
        if (payload == null) payload = new LinkedHashMap<String, Object>();
        String topic = defaultString(payload.get("topic"), "Java 后端");
        String category = defaultString(payload.get("category"), topic);
        String difficulty = defaultString(payload.get("difficulty"), "中等");
        String questionType = defaultString(payload.get("questionType"), "单选");
        String bankType = normalizeBankType(stringValue(payload.get("bankType")), "编程题".equals(questionType) ? "leetcode" : "baguwen");
        if ("leetcode".equals(bankType)) questionType = "编程题";
        String requirements = defaultString(payload.get("requirements"), "结合工程实践、原理理解和排障经验");
        String documentText = stringValue(payload.get("documentText"));
        if (documentText != null && documentText.length() > 600) documentText = documentText.substring(0, 600);
        String sourceHint = documentText == null || documentText.trim().isEmpty() ? "" : " 参考资料：" + documentText.replaceAll("\\s+", " ");
        int count = intValue(payload.get("count"), 5);
        if (count <= 0) count = 5;
        if (count > 20) count = 20;
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> question = new LinkedHashMap<String, Object>();
            question.put("title", topic + " 面试题 " + i);
            question.put("bankType", bankType);
            question.put("category", category);
            question.put("difficulty", difficulty);
            question.put("questionType", questionType);
            question.put("content", generatedContent(topic, requirements + sourceHint, questionType, i));
            question.put("answer", generatedAnswer(topic, requirements, questionType, i));
            List<Map<String, Object>> tags = new ArrayList<Map<String, Object>>();
            addTag(tags, category);
            addTag(tags, topic);
            question.put("tags", tags);
            items.add(saveQuestion(question, null));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        return result;
    }

    private String generatedContent(String topic, String requirements, String questionType, int index) {
        String stem;
        switch ((index - 1) % 5) {
            case 0: stem = "请说明 " + topic + " 中一个核心机制的工作原理，并结合实际项目解释它解决了什么问题。要求：" + requirements; break;
            case 1: stem = "如果线上 " + topic + " 相关能力出现性能下降，你会如何定位、验证和修复？请给出排查步骤。要求：" + requirements; break;
            case 2: stem = "请设计一个与 " + topic + " 相关的工程方案，说明模块划分、关键数据流、异常处理和可观测性设计。要求：" + requirements; break;
            case 3: stem = "请比较 " + topic + " 中两个常见方案的优缺点、适用场景和风险边界。要求：" + requirements; break;
            default: stem = "请结合一次真实或模拟项目经历，说明你如何落地 " + topic + "，包括技术选择、难点和结果指标。要求：" + requirements;
        }
        if ("编程题".equals(questionType)) return stem + "\n\n请任选 Python、Java 或 JavaScript 完成解法，并至少覆盖示例与边界场景。";
        if (!"单选".equals(questionType) && !"多选".equals(questionType)) return stem;
        return stem + "\n\nA. 原理清晰、能结合工程场景并说明边界\nB. 只背诵概念，不说明适用场景\nC. 能给出排查步骤、验证指标和回滚方案\nD. 完全忽略异常处理和性能影响";
    }

    private String generatedAnswer(String topic, String requirements, String questionType, int index) {
        if ("单选".equals(questionType)) return "A";
        if ("多选".equals(questionType)) return "A,C";
        if ("编程题".equals(questionType)) return "以测试用例是否通过作为主要判断标准，同时关注时间复杂度、边界处理和代码可读性。";
        return "参考答案应覆盖：1）核心概念和原理；2）关键流程或架构设计；3）工程实践中的异常、性能和边界处理；4）结合 " + topic + " 的实际项目案例；5）说明权衡理由和可验证结果。补充要求：" + requirements;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createRandomExam(Map<String, Object> payload) {
        if (payload == null) payload = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> selected = new ArrayList<Map<String, Object>>();
        Object questionIdsValue = payload.get("questionIds");
        Object rulesValue = payload.get("rules");
        if (questionIdsValue instanceof List && !((List) questionIdsValue).isEmpty()) {
            java.util.Set<String> used = new java.util.HashSet<String>();
            for (Object idValue : (List) questionIdsValue) {
                String questionId = stringValue(idValue);
                if (questionId == null || questionId.trim().isEmpty() || used.contains(questionId)) continue;
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
                List<Map<String, Object>> pool = interviewRepository.findEnabled(
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
            List<Map<String, Object>> pool = interviewRepository.findEnabled(bankType, category, difficulty, stringValue(payload.get("questionType")));
            Collections.shuffle(pool);
            selected = pool.size() > count ? new ArrayList<Map<String, Object>>(pool.subList(0, count)) : pool;
        }
        if (selected.size() > 50) selected = new ArrayList<Map<String, Object>>(selected.subList(0, 50));
        if (selected.isEmpty()) throw new IllegalArgumentException("当前出题策略下没有可用练习题，请先维护题库或调整组合规则。 ");
        String examId = "practice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String title = defaultString(payload.get("title"), "随机模拟练习");
        int durationMinutes = normalizeDuration(payload.get("durationMinutes"));
        Map<String, Object> strategy = new LinkedHashMap<String, Object>();
        strategy.put("mode", questionIdsValue instanceof List && !((List) questionIdsValue).isEmpty() ? "manual" : "random");
        strategy.put("questionIds", questionIdsValue instanceof List ? questionIdsValue : Collections.emptyList());
        strategy.put("rules", rulesValue instanceof List ? rulesValue : Collections.emptyList());
        strategy.put("durationMinutes", Integer.valueOf(durationMinutes));
        strategy.put("showAnswer", Boolean.valueOf(booleanValue(payload.get("showAnswer"))));
        interviewRepository.createExam(examId, title, durationMinutes, strategy, selected);
        return interviewRepository.findExam(examId);
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

    public Map<String, Object> getExam(String examId) {
        Map<String, Object> exam = interviewRepository.findExam(examId);
        if (exam == null) throw new IllegalArgumentException("练习不存在");
        return exam;
    }

    public List<Map<String, Object>> listExams() {
        return interviewRepository.listExams();
    }

    public Map<String, Object> runCode(Map<String, Object> payload) {
        return interviewCodeRunner.run(payload == null ? Collections.<String, Object>emptyMap() : payload);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitExam(String examId, Map<String, Object> payload) {
        if (payload == null) payload = Collections.emptyMap();
        Map<String, Object> exam = getExam(examId);
        Object answersValue = payload.get("answers");
        Object codingResultsValue = payload.get("codingResults");
        Map<String, Object> answers = answersValue instanceof Map ? (Map<String, Object>) answersValue : Collections.<String, Object>emptyMap();
        Map<String, Object> codingResults = codingResultsValue instanceof Map ? (Map<String, Object>) codingResultsValue : Collections.<String, Object>emptyMap();
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
        return getExam(examId);
    }

    private boolean evaluateQuestion(Map<String, Object> question, String userAnswer, Object codingResult) {
        if ("编程题".equals(stringValue(question.get("questionType"))) || "leetcode".equals(stringValue(question.get("bankType")))) {
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

    /**
     * 简答题按参考答案关键片段覆盖率做轻量判分，命中一半以上视为通过。
     */
    private boolean evaluateShortAnswer(String userAnswer, String expectedAnswer) {
        if (userAnswer == null || userAnswer.trim().isEmpty() || expectedAnswer == null || expectedAnswer.trim().isEmpty()) return false;
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
        if (userAnswer == null || userAnswer.trim().isEmpty() || expectedAnswer == null || expectedAnswer.trim().isEmpty()) return false;
        String user = normalize(userAnswer);
        String expected = normalize(expectedAnswer);
        return user.equals(expected) || user.contains(expected) || expected.contains(user);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "").replace("。", "").replace("，", ",").trim();
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
        if (text.contains("leetcode") || text.contains("leet-code") || text.contains("编程")) return "leetcode";
        if (text.contains("baguwen") || text.contains("八股") || text.contains("选择")) return "baguwen";
        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeCodingMeta(Object value) {
        if (value instanceof Map) return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        return new LinkedHashMap<String, Object>();
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

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return value == null ? defaultValue : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return defaultValue; }
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
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|[\\{,\\s])label\\s*[:=]\\s*([^,}\\]]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) text = matcher.group(1).trim();
        return text.replaceAll("^[\\{\\[\\(]+|[\\}\\]\\)]+$", "")
                .replaceAll("(?i)^label\\s*[:=]\\s*", "")
                .replaceAll("^['\"]|['\"]$", "")
                .trim();
    }
}
