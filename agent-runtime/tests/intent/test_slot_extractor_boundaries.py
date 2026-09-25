import pytest

from app.core.capability.models import ProfileDefinition, SlotExtractorConfig
from app.core.intent.slot_extractor import SlotExtractor
from app.core.intent.text_match import coerce_value, overlap_score, phrase_match


def test_numeric_regex_groups_extract_and_coerce_configured_slots():
    profile = ProfileDefinition(
        id="test",
        name="Test",
        slot_extractors=[
            SlotExtractorConfig(
                name="range",
                type="regex",
                patterns=[r"salary (\d+)-(\d+\.\d+)"],
                target_slots={"minimum": "1", "maximum": "2", "unused": "missing"},
            )
        ],
    )
    assert SlotExtractor().extract(profile, "salary 10-20.5") == {"minimum": 10, "maximum": 20.5}


@pytest.mark.parametrize(("text", "expected"), [("+10", 10), ("-2.5", -2.5), ("senior", "senior")])
def test_slot_value_coercion_preserves_text(text, expected):
    assert coerce_value(text) == expected


def test_text_similarity_requires_meaningful_tokens():
    assert overlap_score(set(), {"python"}) == 0
    assert not phrase_match(" ", "python")
    assert phrase_match("Python", "pythonengineer")
