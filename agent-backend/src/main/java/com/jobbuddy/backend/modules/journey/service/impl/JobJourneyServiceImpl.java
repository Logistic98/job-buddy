package com.jobbuddy.backend.modules.journey.service.impl;

import com.jobbuddy.backend.modules.journey.service.JobJourneyService;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.journey.repository.JobJourneyRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JobJourneyServiceImpl implements JobJourneyService {
    private final JobJourneyRepository repository;
    private final JobBuddyProperties properties;

    public JobJourneyServiceImpl(JobJourneyRepository repository, JobBuddyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Map<String, Object> getTarget(String userId) {
        String effectiveUser = defaultUser(userId);
        Map<String, Object> target = repository.findTarget(effectiveUser);
        if (target == null && !isDefaultUser(effectiveUser)) {
            target = repository.findTarget(defaultUser(null));
        }
        if (target != null) return target;
        Map<String, Object> seed = new LinkedHashMap<String, Object>();
        seed.put("targetId", "target_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        seed.put("userId", effectiveUser);
        seed.put("companyNature", "互联网企业");
        seed.put("companyScale", "不限");
        seed.put("location", "上海");
        seed.put("salaryRange", "40k-50k");
        seed.put("domains", "大模型领域");
        seed.put("positions", "AI原生工程师 / AI应用研发工程师 / AI全栈研发工程师 / AI研发架构师 / AI研发项目负责人");
        seed.put("preferredCompanies", "量化金融公司、米哈游、小红书、AI行业独角兽、小而精的AI创业公司、互联网大厂");
        seed.put("notes", "");
        repository.saveTarget(seed);
        return repository.findTarget(effectiveUser);
    }

    public Map<String, Object> saveTarget(String userId, Map<String, Object> payload) {
        String effectiveUser = defaultUser(userId);
        Map<String, Object> current = repository.findTarget(effectiveUser);
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("targetId", stringOrDefault(payload.get("targetId"), current == null ? "target_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : String.valueOf(current.get("targetId"))));
        target.put("userId", effectiveUser);
        target.put("companyNature", string(payload.get("companyNature")));
        target.put("companyScale", string(payload.get("companyScale")));
        target.put("location", string(payload.get("location")));
        target.put("salaryRange", string(payload.get("salaryRange")));
        target.put("domains", string(payload.get("domains")));
        target.put("positions", string(payload.get("positions")));
        target.put("preferredCompanies", string(payload.get("preferredCompanies")));
        target.put("notes", string(payload.get("notes")));
        repository.saveTarget(target);
        return repository.findTarget(effectiveUser);
    }

    public List<Map<String, Object>> listRecords(String userId, String keyword, String status, String result) {
        String effectiveUser = defaultUser(userId);
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>(repository.listRecords(effectiveUser, keyword, status, result));
        if (!isDefaultUser(effectiveUser)) {
            appendLegacyDefaultUserRecords(records, keyword, status, result);
        }
        return records;
    }

    public Map<String, Object> getRecord(String recordId) {
        return repository.findRecord(recordId);
    }

    public Map<String, Object> saveRecord(String userId, Map<String, Object> payload, String recordId) {
        String effectiveUser = defaultUser(userId);
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("recordId", stringOrDefault(recordId, "journey_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
        record.put("userId", effectiveUser);
        record.put("company", stringOrDefault(payload.get("company"), "未命名企业"));
        record.put("city", string(payload.get("city")));
        record.put("companyNature", string(payload.get("companyNature")));
        record.put("companyScale", string(payload.get("companyScale")));
        record.put("positionName", string(payload.get("positionName")));
        record.put("salaryRange", string(payload.get("salaryRange")));
        record.put("favoriteKey", string(payload.get("favoriteKey")));
        record.put("businessDirection", string(payload.get("businessDirection")));
        record.put("interviewRound", string(payload.get("interviewRound")));
        record.put("interviewTime", string(payload.get("interviewTime")));
        record.put("interviewContent", string(payload.get("interviewContent")));
        record.put("interviewFormat", string(payload.get("interviewFormat")));
        record.put("result", stringOrDefault(payload.get("result"), "跟进中"));
        record.put("reflection", string(payload.get("reflection")));
        record.put("jobDescription", string(payload.get("jobDescription")));
        record.put("interviewProcess", string(payload.get("interviewProcess")));
        record.put("nextAction", string(payload.get("nextAction")));
        record.put("status", stringOrDefault(payload.get("status"), "面试进展"));
        record.put("priority", stringOrDefault(payload.get("priority"), "中"));
        record.put("tags", normalizeTags(payload.get("tags")));
        record.put("enabled", Boolean.TRUE);
        repository.saveRecord(record);
        return repository.findRecord(String.valueOf(record.get("recordId")));
    }

    public void deleteRecord(String recordId) {
        repository.deleteRecord(recordId);
    }

    public Map<String, Object> analyzeProgress(String userId, Map<String, Object> payload) {
        String effectiveUser = defaultUser(userId);
        List<Map<String, Object>> records = repository.listRecords(effectiveUser, null, null, null);
        List<String> selectedRecordIds = normalizeStringList(payload.get("recordIds"));
        if (!selectedRecordIds.isEmpty()) {
            List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> row : records) {
                if (selectedRecordIds.contains(string(row.get("recordId")))) filtered.add(row);
            }
            records = filtered;
        }
        String recordId = string(payload.get("recordId"));
        Map<String, Object> focus = recordId.isEmpty() ? null : repository.findRecord(recordId);
        if (focus == null && !records.isEmpty()) focus = records.get(0);
        Map<String, Object> target = getTarget(effectiveUser);

        int total = records.size();
        int active = 0, passed = 0, failed = 0, pending = 0, offer = 0, highPriority = 0;
        Map<String, Integer> roundCount = new LinkedHashMap<String, Integer>();
        Map<String, Integer> domainCount = new LinkedHashMap<String, Integer>();
        List<Map<String, Object>> followUps = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> weakSignals = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : records) {
            String result = string(row.get("result"));
            String status = string(row.get("status"));
            if ("通过".equals(result)) passed++;
            else if ("未通过".equals(result)) failed++;
            else if ("待反馈".equals(result) || "跟进中".equals(result)) pending++;
            if ("Offer".equals(status) || result.contains("Offer")) offer++;
            if (!"结束".equals(status) && !"未通过".equals(result) && !"已放弃".equals(result)) active++;
            if ("高".equals(row.get("priority"))) highPriority++;
            addCount(roundCount, stringOrDefault(row.get("interviewRound"), "未标注"));
            addCount(domainCount, stringOrDefault(row.get("businessDirection"), "未标注"));
            if (needsFollowUp(row)) followUps.add(row);
            if (hasWeakSignal(row)) weakSignals.add(row);
        }

        int score = Math.min(95, Math.max(35, 45 + active * 8 + passed * 12 + offer * 18 + highPriority * 4 - failed * 6 - weakSignals.size() * 3));
        List<String> strengths = new ArrayList<String>();
        if (active > 0) strengths.add("当前仍有 " + active + " 条机会处于推进中，可以继续拉动反馈和后续轮次。 ");
        if (passed > 0) strengths.add("已有通过记录，说明简历和部分面试表现得到验证，建议复用对应准备材料。 ");
        if (!domainCount.isEmpty()) strengths.add("求职方向集中在「" + topKey(domainCount) + "」，便于沉淀一套可复用的项目和技术表达。 ");
        if (strengths.isEmpty()) strengths.add("已开始建立求职台账，后续需要持续补全岗位 JD、面试内容和复盘。 ");

        List<String> risks = new ArrayList<String>();
        if (total == 0) risks.add("当前还没有进展记录，无法判断漏斗转化情况。先补充最近 3-5 条投递或面试记录。 ");
        if (pending > 0) risks.add("有 " + pending + " 条记录仍在跟进或待反馈，建议设置明确跟进时间，避免机会静默流失。 ");
        if (weakSignals.size() > 0) risks.add("有 " + weakSignals.size() + " 条记录缺少面试内容、复盘或下一步动作，后续难以针对性改进。 ");
        if (failed > passed && failed > 0) risks.add("未通过记录偏多，需要从技术短板、项目表达、岗位匹配三个维度拆解原因。 ");
        if (risks.isEmpty()) risks.add("当前记录没有明显风险，但仍建议每次面试后当天完成复盘。 ");

        List<String> nextActions = new ArrayList<String>();
        if (focus != null) {
            nextActions.add("针对「" + stringOrDefault(focus.get("company"), "该企业") + " - " + stringOrDefault(focus.get("positionName"), "该岗位") + "」，先补齐 JD、面试内容、复盘和下一步动作，再准备下一轮问题清单。 ");
        }
        if (!followUps.isEmpty()) nextActions.add("优先跟进「" + stringOrDefault(followUps.get(0).get("company"), "待反馈企业") + "」，用简短礼貌话术确认结果或下一轮安排。 ");
        nextActions.add("把高频业务方向「" + (domainCount.isEmpty() ? stringOrDefault(target.get("domains"), "目标方向") : topKey(domainCount)) + "」整理成 3 分钟项目介绍、核心难点、指标收益和追问答案。 ");
        nextActions.add("为下一场面试准备一份清单：岗位 JD 对齐点、项目亮点、技术短板补强、反问问题和期望薪资边界。 ");

        List<String> preparationPlan = new ArrayList<String>();
        preparationPlan.add("今天：补全最近记录的面试内容和复盘，标记每条记录的下一步动作。 ");
        preparationPlan.add("3 天内：围绕最高频方向整理 10 个技术追问和 5 个项目深挖问题。 ");
        preparationPlan.add("一周内：根据通过/未通过记录复盘投递画像，保留高匹配岗位，减少低匹配消耗。 ");

        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("total", total);
        metrics.put("active", active);
        metrics.put("passed", passed);
        metrics.put("failed", failed);
        metrics.put("pending", pending);
        metrics.put("offer", offer);
        metrics.put("score", score);
        metrics.put("topRound", topKey(roundCount));
        metrics.put("topDomain", topKey(domainCount));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("summary", buildAnalysisSummary(total, active, passed, failed, pending, score));
        result.put("metrics", metrics);
        result.put("strengths", strengths);
        result.put("risks", risks);
        result.put("nextActions", nextActions);
        result.put("preparationPlan", preparationPlan);
        result.put("followUpMessage", buildFollowUpMessage(focus != null ? focus : (!followUps.isEmpty() ? followUps.get(0) : null)));
        result.put("generatedAt", java.time.Instant.now().toString());
        return result;
    }

    private String defaultUser(String userId) {
        return (userId == null || userId.isEmpty()) ? properties.getDefaultUserId() : userId;
    }

    private void appendLegacyDefaultUserRecords(List<Map<String, Object>> records, String keyword, String status, String result) {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Map<String, Object> record : records) {
            String recordId = string(record.get("recordId"));
            if (!recordId.isEmpty()) seen.add(recordId);
        }
        for (Map<String, Object> record : repository.listRecords(defaultUser(null), keyword, status, result)) {
            String recordId = string(record.get("recordId"));
            if (recordId.isEmpty() || seen.contains(recordId)) continue;
            records.add(record);
            seen.add(recordId);
        }
    }

    private boolean isDefaultUser(String userId) {
        return defaultUser(null).equals(userId);
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private String stringOrDefault(Object value, String fallback) {
        String text = string(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private void addCount(Map<String, Integer> counts, String key) {
        if (key == null || key.trim().isEmpty()) key = "未标注";
        counts.put(key, Integer.valueOf(counts.containsKey(key) ? counts.get(key).intValue() + 1 : 1));
    }

    private String topKey(Map<String, Integer> counts) {
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue().intValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue().intValue();
            }
        }
        return best;
    }

    private boolean needsFollowUp(Map<String, Object> row) {
        String result = string(row.get("result"));
        return "待反馈".equals(result) || "跟进中".equals(result) || string(row.get("nextAction")).trim().isEmpty();
    }

    private boolean hasWeakSignal(Map<String, Object> row) {
        return string(row.get("interviewContent")).trim().isEmpty()
                || string(row.get("reflection")).trim().isEmpty()
                || string(row.get("nextAction")).trim().isEmpty();
    }

    private String buildAnalysisSummary(int total, int active, int passed, int failed, int pending, int score) {
        if (total == 0) return "当前还没有可分析的面试进展。建议先录入投递、笔试、面试和反馈记录，系统会基于漏斗状态给出建议。";
        return "当前共分析 " + total + " 条求职进展，其中推进中 " + active + " 条、通过 " + passed + " 条、未通过 " + failed + " 条、待反馈/跟进中 " + pending + " 条。综合推进健康度约为 " + score + " 分，建议优先补齐复盘和下一步动作。";
    }

    private List<String> normalizeStringList(Object raw) {
        List<String> result = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object item : (List) raw) {
                String value = string(item).trim();
                if (!value.isEmpty() && !result.contains(value)) result.add(value);
            }
        } else if (raw != null) {
            String[] parts = String.valueOf(raw).split("[,，、\\s]+");
            for (String part : parts) {
                String value = part.trim();
                if (!value.isEmpty() && !result.contains(value)) result.add(value);
            }
        }
        return result;
    }

    private String buildFollowUpMessage(Map<String, Object> row) {
        if (row == null) return "您好，我想跟进一下之前沟通的岗位进展。如果有后续安排或需要补充材料，我可以及时配合提供，谢谢。";
        String company = stringOrDefault(row.get("company"), "贵司");
        String position = stringOrDefault(row.get("positionName"), "相关岗位");
        String round = stringOrDefault(row.get("interviewRound"), "面试");
        return "您好，我想跟进一下「" + company + " - " + position + "」" + round + " 后续进展。如果有下一轮安排或需要补充材料，我可以及时配合提供，谢谢。";
    }

    private List<Map<String, Object>> normalizeTags(Object raw) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (raw instanceof List) {
            for (Object item : (List) raw) addTag(result, item);
        } else if (raw != null) {
            String[] parts = String.valueOf(raw).split("[,，、\\s]+");
            for (String part : parts) addTag(result, part);
        }
        return result;
    }

    private void addTag(List<Map<String, Object>> result, Object raw) {
        String label = raw instanceof Map ? string(((Map) raw).get("label")) : string(raw);
        if (label.trim().isEmpty()) return;
        Map<String, Object> tag = new LinkedHashMap<String, Object>();
        tag.put("label", label.trim());
        result.add(tag);
    }
}
