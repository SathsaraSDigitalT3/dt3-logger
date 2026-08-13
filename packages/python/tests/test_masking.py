"""Tests for DT3 Commons Python sensitive-data masking."""

from __future__ import annotations

import json

from dt3_sdk import MaskingEngine, create_logger


def test_masks_default_sensitive_fields_case_insensitively() -> None:
    engine = MaskingEngine()

    masked, tracked_fields = engine.mask(
        {
            "Password": "p@ssw0rd",
            "EMAIL": "person@example.com",
            "safe": "visible",
        }
    )

    assert masked == {
        "Password": "[REDACTED]",
        "EMAIL": "[REDACTED]",
        "safe": "visible",
    }
    assert tracked_fields == []


def test_masks_nested_objects_and_lists_without_mutating_input() -> None:
    source = {
        "account": {
            "token": "top-secret",
            "contacts": [
                {"email": "first@example.com"},
                {"phone": "555-0100"},
            ],
        }
    }
    engine = MaskingEngine(track_masked_fields=True)

    masked, tracked_fields = engine.mask(source)

    assert source["account"]["token"] == "top-secret"
    assert source["account"]["contacts"][0]["email"] == "first@example.com"
    assert source["account"]["contacts"][1]["phone"] == "555-0100"
    assert masked == {
        "account": {
            "token": "[REDACTED]",
            "contacts": [
                {"email": "[REDACTED]"},
                {"phone": "[REDACTED]"},
            ],
        }
    }
    assert tracked_fields == [
        "account.token",
        "account.contacts[0].email",
        "account.contacts[1].phone",
    ]


def test_supports_custom_sensitive_fields_and_replacement_value() -> None:
    engine = MaskingEngine(
        sensitive_fields=["internalId"],
        replacement_value="***",
        track_masked_fields=True,
    )

    masked, tracked_fields = engine.mask({"INTERNALID": "customer-123", "name": "Ada"})

    assert masked == {"INTERNALID": "***", "name": "Ada"}
    assert tracked_fields == ["INTERNALID"]


def test_disabled_masking_returns_an_independent_unmasked_copy() -> None:
    source = {"password": "secret", "nested": {"token": "token-value"}}
    engine = MaskingEngine(enabled=False, track_masked_fields=True)

    masked, tracked_fields = engine.mask(source)
    masked["nested"]["token"] = "changed"

    assert source == {"password": "secret", "nested": {"token": "token-value"}}
    assert tracked_fields == []


def test_logger_masks_before_stdout_export_and_tracks_paths(capsys) -> None:
    logger = create_logger(
        {
            "service.name": "test-service",
            "service.version": "1.0.0",
            "deployment.environment": "test",
            "exporter": "stdout",
            "masking.track_masked_fields": True,
        }
    )
    context = {
        "event.name": "USER_LOGIN",
        "password": "secret",
        "attributes": {"authorization": "Bearer credential"},
    }

    logger.info("User logged in", context)

    exported_event = json.loads(capsys.readouterr().out)
    assert exported_event["password"] == "[REDACTED]"
    assert exported_event["attributes"]["authorization"] == "[REDACTED]"
    assert exported_event["dt3.security.masked_fields"] == [
        "password",
        "attributes.authorization",
    ]
    assert context["password"] == "secret"
    assert context["attributes"]["authorization"] == "Bearer credential"


def test_logger_respects_masking_enabled_configuration(capsys) -> None:
    logger = create_logger(
        {
            "service.name": "test-service",
            "service.version": "1.0.0",
            "deployment.environment": "test",
            "exporter": "stdout",
            "masking.enabled": False,
        }
    )

    logger.info("Masking disabled", {"event.name": "MASKING_DISABLED", "password": "secret"})

    exported_event = json.loads(capsys.readouterr().out)
    assert exported_event["password"] == "secret"
    assert "dt3.security.masked_fields" not in exported_event
