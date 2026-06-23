from app.tools.boss_browser.core.extract import extract_jobs, normalize_detail


def test_extract_jobs_from_zpdata():
    payload = {
        "code": 0,
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


def test_extract_jobs_normalizes_nested_salary_fields():
    payload = {
        "zpData": {
            "jobList": [
                {
                    "jobInfo": {
                        "security_id": "sid-1",
                        "positionName": "大模型应用工程师",
                        "salaryName": "40-60K",
                        "experienceName": "3-5年",
                    },
                    "brandInfo": {"companyName": "示例科技", "industryName": "互联网"},
                }
            ]
        }
    }
    jobs = extract_jobs(payload)
    assert jobs[0]["securityId"] == "sid-1"
    assert jobs[0]["jobName"] == "大模型应用工程师"
    assert jobs[0]["salaryDesc"] == "40-60K"
    assert jobs[0]["brandName"] == "示例科技"


def test_extract_jobs_formats_numeric_salary_bounds():
    payload = {"zpData": {"jobList": [{"jobName": "后端", "lowSalary": 30000, "highSalary": 50000}]}}
    jobs = extract_jobs(payload)
    assert jobs[0]["salaryDesc"] == "30-50K"


def test_extract_jobs_empty_on_none():
    assert extract_jobs(None) == []
    assert extract_jobs({"zpData": {}}) == []


def test_normalize_detail_maps_description():
    payload = {
        "zpData": {
            "jobInfo": {
                "postDescription": "负责后端服务开发",
                "jobName": "后端工程师",
                "salaryDesc": "20-35K",
            },
            "brandComInfo": {"brandName": "示例公司", "industryName": "互联网"},
        }
    }
    detail = normalize_detail(payload)
    assert detail["jobDescription"] == "负责后端服务开发"
    assert detail["jobName"] == "后端工程师"
    assert detail["salaryDesc"] == "20-35K"
    assert detail["brandName"] == "示例公司"
    assert "_raw" in detail


def test_normalize_detail_accepts_dom_fallback_shape():
    detail = normalize_detail({"jobSecText": "岗位职责\n1. 负责 Agent 应用开发", "salaryName": "35-55K"})
    assert detail["jobDescription"] == "岗位职责\n1. 负责 Agent 应用开发"
    assert detail["salaryDesc"] == "35-55K"
