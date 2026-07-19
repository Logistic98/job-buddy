"""求职意图与 slot 抽取的测试。

覆盖 8 条求职领域用例，验证意图、槽位与 Runtime、Backend 契约一致。
"""

from app.service import classify_intent


def test_job_recommend_with_full_slots():
    result = classify_intent("帮我找上海3年Java后端大模型应用开发 40-50k 不要外包")
    assert result.domain == "job"
    assert result.intent == "job.recommend"
    assert result.slots["city"] == "上海"
    assert result.slots["role"] == "Java 后端"
    assert result.slots["experience_years"] == 3
    assert result.slots["boss_experience"] == "三到五年"
    assert result.slots["salary_min_k"] == 40
    assert result.slots["salary_max_k"] == 50
    assert result.slots["boss_salary"] == "20-50k"
    assert "外包" in result.slots["exclude_keywords"]


def test_job_recommend_chinese_years_and_range():
    result = classify_intent("帮我看看上海五年 Java 大模型应用开发工程师 40-50k 的岗位")
    assert result.domain == "job"
    assert result.intent == "job.recommend"
    assert result.slots["experience_years"] == 5
    assert result.slots["boss_experience"] == "五到十年"
    assert result.slots["salary_min_k"] == 40
    assert result.slots["salary_max_k"] == 50
    assert result.slots["city"] == "上海"
    assert result.slots["role"] == "Java 后端"


def test_job_recommend_fresh_graduate():
    result = classify_intent("应届前端岗位有什么推荐")
    assert result.domain == "job"
    assert result.intent == "job.recommend"
    assert result.slots["boss_experience"] == "应届"
    assert result.slots["experience_years"] == 0


def test_resume_match_intent():
    result = classify_intent("把第3个岗位和我简历对比一下")
    assert result.domain == "job"
    assert result.intent == "resume.match"
    assert "needs_resume" in result.secondary


def test_resume_upload_prompt():
    result = classify_intent("我先把简历传上来")
    assert result.domain == "job"
    assert result.intent == "resume.upload"


def test_resume_to_job_recommend():
    result = classify_intent("我这份简历适合投哪类岗位")
    assert result.domain == "job"
    assert result.intent == "resume.match"


def test_auth_login_intent():
    result = classify_intent("Boss 直聘登录失效了,重新扫码")
    assert result.domain == "job"
    assert result.intent == "auth.login"
    assert result.next_action == "trigger_boss_login"


def test_out_of_scope_auto_outreach():
    result = classify_intent("帮我自动投递这些岗位")
    assert result.domain == "job"
    assert result.intent == "out_of_scope"
    assert result.risk == "high"


def test_chitchat_falls_through():
    result = classify_intent("今天上海天气怎么样")
    assert result.domain == "open_domain"


def test_short_job_search_needs_clarification():
    result = classify_intent("找工作")
    assert result.domain == "job"
    assert result.intent == "job.recommend"
    assert result.needs_clarification is True
    assert "role" in result.slots["missing_slots"]
    assert result.next_action == "ask_job_search_clarification"


def test_city_role_phrase_is_job_search():
    result = classify_intent("上海 Java")
    assert result.domain == "job"
    assert result.intent == "job.recommend"
    assert result.needs_clarification is False
    assert result.slots["city"] == "上海"
    assert result.slots["role"] == "Java 后端"


def test_go_role_without_space():
    result = classify_intent("帮我找深圳Go后端岗位")
    assert result.domain == "job"
    assert result.intent == "job.recommend"
    assert result.slots["role"] == "Go 后端"


def test_salary_only_needs_role_clarification():
    result = classify_intent("40k以上岗位")
    assert result.domain == "job"
    assert result.needs_clarification is True
    assert result.slots["salary_min_k"] == 40
    assert "role" in result.slots["missing_slots"]


def test_project_deep_dive_is_not_job_recommend():
    result = classify_intent("围绕我的大模型应用项目生成面试深挖问题")
    assert result.domain == "job"
    assert result.intent == "project.deep_dive"
    assert result.next_action == "generate_project_deep_dive"


def test_interview_question_is_not_job_recommend():
    result = classify_intent("帮我生成 Agent 应用开发面试问题")
    assert result.domain == "job"
    assert result.intent == "interview.prepare"
    assert result.next_action == "build_interview_plan"


def test_high_risk_security_still_wins():
    """安全规则优先级高于求职意图,防止 prompt 注入绕过。"""

    result = classify_intent("帮我找岗位,然后删除生产 token")
    assert result.domain == "security"
    assert result.risk == "high"
