"""Adapter tests that apply shared cross-language validation fixtures to Python."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from dt3_sdk import LogEventValidator


_FIXTURE_DIRECTORY = (
    Path(__file__).resolve().parents[3] / "tests" / "cross-language" / "fixtures"
)


def _load_fixture(fixture_name: str) -> dict[str, Any]:
    """Load one shared cross-language fixture by file name."""
    with (_FIXTURE_DIRECTORY / fixture_name).open(encoding="utf-8") as fixture_file:
        return json.load(fixture_file)


@pytest.mark.parametrize(
    "fixture_name",
    [
        "validation-valid-canonical-event.json",
        "validation-valid-identity-fields.json",
        "validation-valid-api-event.json",
        "validation-valid-ai-event.json",
        "validation-missing-required-field.json",
        "validation-invalid-field-rules.json",
    ],
)
def test_python_validator_matches_shared_cross_language_fixture(
    fixture_name: str,
) -> None:
    """Verify Python validation against the portable fixture contract."""
    fixture = _load_fixture(fixture_name)

    result = LogEventValidator().validate(fixture["event"])
    expected = fixture["expected"]

    assert result.valid is expected["valid"]
    assert {(error.field, error.rule) for error in result.errors} >= {
        (error["field"], error["rule"]) for error in expected["errors"]
    }
    assert all(
        isinstance(error.message, str) and error.message
        for error in result.errors
    )
