from app.tools.boss_browser.core.extract import extract_favorite_jobs, extract_jobs, normalize_detail


def test_extract_jobs_from_zpdata():
    payload = {
        "code": 200,
        "zpData": {
            "lid": "abc123",
            "jobList": [
                {"securityId": "s1", "jobName": "Java 工程师"},
                {"securityId": "s2", "jobName": "Python 工程师"},
            ],
        },
    }
    jobs = extract_jobs(payload)
    assert len(jobs) == 2
    assert jobs[0]["securityId"] == "s1"
    # 页级 lid 回填到每行，便于下游详情拼链。
    assert jobs[0]["lid"] == "abc123"


def test_extract_jobs_from_real_favorite_card_list_without_duplicating_labels_as_skills():
    payload = {
        "zpData": {
            "lid": "favorite-page-lid",
            "cardList": [
                {
                    "securityId": "favorite-1",
                    "jobName": "AI 算法工程师",
                    "jobLabels": ["杭州", "1-3年", "本科"],
                    "postDescription": "负责大模型算法研发与落地",
                }
            ],
        }
    }

    jobs = extract_jobs(payload)

    assert jobs[0]["lid"] == "favorite-page-lid"
    assert jobs[0]["jobLabels"] == ["杭州", "1-3年", "本科"]
    assert "skills" not in jobs[0]
    assert jobs[0]["jobDescription"] == "负责大模型算法研发与落地"


def test_extract_favorite_jobs_normalizes_real_boss_action_time():
    payload = {
        "zpData": {
            "cardList": [
                {
                    "securityId": "favorite-1",
                    "jobName": "AI 算法工程师",
                    "actionDateDesc": "2025年10月02日 09:33",
                    "happenTime": 1759368793000,
                }
            ]
        }
    }

    jobs = extract_favorite_jobs(payload)

    assert jobs[0]["favoritedAt"] == "2025-10-02T01:33:13Z"
    assert jobs[0]["actionDateDesc"] == "2025年10月02日 09:33"
    assert jobs[0]["happenTime"] == 1759368793000


def test_extract_favorite_jobs_falls_back_to_explicit_action_date_without_guessing_invalid_values():
    payload = {
        "cardList": [
            {"securityId": "favorite-1", "actionDateDesc": "2025年10月02日 09:33"},
            {"securityId": "favorite-2", "actionDateDesc": "最近收藏", "happenTime": "invalid"},
        ]
    }

    jobs = extract_favorite_jobs(payload)

    assert jobs[0]["favoritedAt"] == "2025-10-02T01:33:00Z"
    assert "favoritedAt" not in jobs[1]


def test_extract_jobs_normalizes_nested_salary_fields():
    payload = {
        "zpData": {
            "jobList": [
                {
                    "jobInfo": {
                        "security_id": "sid-1",
                        "positionName": "Go 云原生平台开发工程师",
                        "salaryName": "25-35K",
                        "experienceName": "3-5年",
                    },
                    "brandInfo": {"companyName": "示例科技", "industryName": "互联网"},
                }
            ]
        }
    }
    jobs = extract_jobs(payload)
    assert jobs[0]["securityId"] == "sid-1"
    assert jobs[0]["jobName"] == "Go 云原生平台开发工程师"
    assert jobs[0]["salaryDesc"] == "25-35K"
    assert jobs[0]["brandName"] == "示例科技"


def test_extract_jobs_formats_numeric_salary_bounds():
    payload = {"zpData": {"jobList": [{"jobName": "Go 云原生平台开发", "lowSalary": 25000, "highSalary": 35000}]}}
    jobs = extract_jobs(payload)
    assert jobs[0]["salaryDesc"] == "25-35K"


def test_extract_jobs_empty_on_none():
    assert extract_jobs(None) == []
    assert extract_jobs({"zpData": {}}) == []


def test_normalize_detail_maps_description():
    payload = {
        "zpData": {
            "jobInfo": {
                "postDescription": "负责基于 Java 的大模型应用服务开发",
                "jobName": "Go 云原生平台开发工程师",
                "salaryDesc": "25-35K",
            },
            "brandComInfo": {"brandName": "示例公司", "industryName": "互联网"},
        }
    }
    detail = normalize_detail(payload)
    assert detail["jobDescription"] == "负责基于 Java 的大模型应用服务开发"
    assert detail["jobName"] == "Go 云原生平台开发工程师"
    assert detail["salaryDesc"] == "25-35K"
    assert detail["brandName"] == "示例公司"
    assert "_raw" in detail


def test_normalize_detail_accepts_dom_fallback_shape():
    detail = normalize_detail({"jobSecText": "岗位职责\n1. 负责 Go 云原生平台开发", "salaryName": "25-35K"})
    assert detail["jobDescription"] == "岗位职责\n1. 负责 Go 云原生平台开发"
    assert detail["salaryDesc"] == "25-35K"
