package com.jobbuddy.backend.modules.project.service.impl;

import com.jobbuddy.backend.modules.project.service.ProjectDeepDiveService;

import com.jobbuddy.backend.modules.project.repository.ProjectDeepDiveRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectDeepDiveServiceImpl implements ProjectDeepDiveService {
    private final ProjectDeepDiveRepository repository;

    public ProjectDeepDiveServiceImpl(ProjectDeepDiveRepository repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> listProjects() {
        return repository.listProjects();
    }

    public Map<String, Object> saveProject(Map<String, Object> payload, String projectId) {
        Map<String, Object> project = new LinkedHashMap<String, Object>();
        project.put("projectId", projectId == null || projectId.trim().isEmpty() ? "pdd_" + randomId() : projectId);
        project.put("name", required(payload, "name", "项目名称不能为空"));
        project.put("role", defaultString(payload.get("role"), "核心开发"));
        project.put("summary", defaultString(payload.get("summary"), ""));
        project.put("techStack", defaultString(payload.get("techStack"), ""));
        repository.saveProject(project);
        return repository.findProject(String.valueOf(project.get("projectId")));
    }

    public void deleteProject(String projectId) {
        repository.deleteProject(projectId);
    }

    public Map<String, Object> addMaterial(String projectId, Map<String, Object> payload) {
        if (repository.findProject(projectId) == null) throw new IllegalArgumentException("项目不存在");
        Map<String, Object> material = new LinkedHashMap<String, Object>();
        material.put("materialId", "mat_" + randomId());
        material.put("projectId", projectId);
        material.put("fileName", defaultString(payload.get("fileName"), "项目材料"));
        material.put("contentType", defaultString(payload.get("contentType"), "text/plain"));
        material.put("content", required(payload, "content", "项目材料内容不能为空"));
        repository.saveMaterial(material);
        return repository.findProject(projectId);
    }

    public void deleteMaterial(String materialId) {
        repository.deleteMaterial(materialId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateQuestions(String projectId, Map<String, Object> payload) {
        Map<String, Object> project = repository.findProject(projectId);
        if (project == null) throw new IllegalArgumentException("项目不存在");
        int count = intValue(payload == null ? null : payload.get("count"), 12);
        count = Math.max(4, Math.min(count, 40));
        String focus = defaultString(payload == null ? null : payload.get("focus"), "架构设计、技术难点、项目结果、排障复盘");
        String materialText = materialsText((List<Map<String, Object>>) project.get("materials"));
        if (materialText.trim().isEmpty()) materialText = defaultString(project.get("summary"), "项目材料不足，请结合项目背景补充回答。 ");
        List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
        String[] categories = new String[]{"项目背景", "架构设计", "技术难点", "业务价值", "性能稳定性", "协作复盘"};
        for (int i = 0; i < count; i++) {
            String category = categories[i % categories.length];
            questions.add(question(project, category, focus, materialText, i + 1));
        }
        repository.replaceQuestions(projectId, questions);
        return repository.findProject(projectId);
    }

    private Map<String, Object> question(Map<String, Object> project, String category, String focus, String materialText, int index) {
        String name = String.valueOf(project.get("name"));
        String role = defaultString(project.get("role"), "核心开发");
        String tech = defaultString(project.get("techStack"), "项目相关技术栈");
        String evidence = evidence(materialText, index);
        String question;
        String answer;
        if ("项目背景".equals(category)) {
            question = "你为什么要做“" + name + "”这个项目？它解决了什么真实问题，你负责的边界是什么？";
            answer = "可以先说明业务背景和痛点，再说明项目目标，最后落到个人职责。回答重点：我在项目中担任" + role + "，围绕" + focus + "推进落地；可结合材料说明：" + evidence;
        } else if ("架构设计".equals(category)) {
            question = "“" + name + "”的整体架构是怎样的？核心模块之间如何协作？";
            answer = "建议按入口层、业务层、数据层、异步任务或外部依赖说明。突出为什么这样拆分、如何保证扩展性和可维护性。技术栈可提到：" + tech + "。材料依据：" + evidence;
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
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("questionId", "pdq_" + randomId());
        item.put("question", question);
        item.put("answer", answer);
        item.put("category", category);
        item.put("difficulty", index % 3 == 0 ? "深入" : "常规");
        item.put("source", "generated");
        return item;
    }

    private String materialsText(List<Map<String, Object>> materials) {
        StringBuilder builder = new StringBuilder();
        if (materials == null) return "";
        for (Map<String, Object> material : materials) {
            builder.append('\n').append(defaultString(material.get("content"), ""));
            if (builder.length() > 5000) break;
        }
        return builder.toString();
    }

    private String evidence(String text, int index) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return "暂无明确材料，需要结合实际经历补充。";
        int start = Math.min(Math.max(0, (index - 1) * 120), Math.max(0, clean.length() - 1));
        return clean.substring(start, Math.min(clean.length(), start + 220));
    }

    private String required(Map<String, Object> payload, String key, String message) {
        String value = payload == null ? null : stringValue(payload.get(key));
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private String defaultString(Object value, String fallback) {
        String text = stringValue(value);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private String randomId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
}
