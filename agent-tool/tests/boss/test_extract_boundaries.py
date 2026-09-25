import pytest

from app.tools.boss_browser.core.extract import (
    assemble_profile,
    extract_favorite_jobs,
    extract_jobs,
    has_salary_evidence,
    normalize_detail,
)


def test_missing_or_deeply_nested_payload_produces_no_jobs():
    assert extract_jobs(None) == []
    assert has_salary_evidence(None) is False
    payload = [{"jobName": "job"}]
    for _ in range(8):
        payload = {"data": payload}
    assert extract_jobs(payload) == []
    assert extract_jobs({"unknown": {"jobs": [{"jobName": "job"}]}})[0]["jobName"] == "job"
    assert normalize_detail(None) == {"_raw": None}


@pytest.mark.parametrize(
    ("bounds", "salary"),
    [
        ({"lowSalary": "12500元", "highSalary": "20K"}, "12.5-20K"),
        ({"lowSalary": 15}, "15K以上"),
        ({"highSalary": 20000}, "20K以内"),
    ],
)
def test_structured_salary_bounds_normalize_units(bounds, salary):
    job = extract_jobs([{"nested": [bounds], "skillList": [" Java ", "", " Python "]}])[0]
    assert job["salaryDesc"] == salary
    assert job["skills"] == ["Java", "Python"]


@pytest.mark.parametrize("bounds", [{"lowSalary": "unknown"}, {"lowSalary": -1}])
def test_invalid_salary_bounds_do_not_fabricate_salary(bounds):
    assert "salaryDesc" not in extract_jobs([bounds])[0]


def test_favorite_time_invalid_timestamp_falls_back_to_china_date():
    jobs = extract_favorite_jobs([{"happenTime": "invalid", "actionDateDesc": "2026年01月01日 08:00"}])
    assert jobs[0]["favoritedAt"] == "2026-01-01T00:00:00Z"
    assert "favoritedAt" not in extract_favorite_jobs([{"happenTime": "invalid", "actionDateDesc": "invalid"}])[0]


def test_profile_sections_preserve_first_match_and_unknown_payloads():
    result = assemble_profile(
        [
            ("https://boss.invalid/resume/baseinfo", {"zpData": {"name": "first"}}),
            ("https://boss.invalid/resume/base", {"data": {"name": "second"}}),
            ("https://boss.invalid/unknown", {"value": "raw"}),
        ]
    )
    assert result["basicInfo"] == {"name": "first"}
    assert result["sections"]["https://boss.invalid/unknown"] == {"value": "raw"}
    assert result["jobStatus"] == {}


def test_existing_favorite_time_is_preserved_and_deep_detail_is_bounded():
    assert extract_favorite_jobs([{"favoritedAt": "existing"}])[0]["favoritedAt"] == "existing"
    nested = {"jobName": "too deep"}
    for _ in range(8):
        nested = {"nested": nested}
    assert "jobName" not in normalize_detail(nested)
