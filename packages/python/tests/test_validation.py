"""Tests for repository-defined DT3 log-event schema validation."""

from __future__ import annotations

import json

import pytest

from dt3_sdk import LogEventValidator, ValidationError, create_logger


def _config(validation_mode: str = "LENIENT", **overrides: object) -> dict[str, object]:
    """Build the minimum SDK configuration needed for test events."""
    return {
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "stdout",
        "validation.mode": validation_mode,
        **overrides,
    }


def test_validator_accepts_canonical_event_with_nested_context_tenant_and_error_fields() -> None:
    event = {
        "timestamp": "2026-08-13T12:00:00+00:00",
        "severity": "ERROR",
        "message": "Request failed",
        "event.name": "REQUEST_FAILED",
        "schema.version": "1.0.0",
        "sdk.name": "dt3-python",
        "sdk.version": "0.1.0",
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "tenant.id": "tenant-42",
        "tenant.region": "us-east",
        "error.type": "RuntimeError",
        "error.message": "operation failed",
        "error.retryable": False,
        "attributes": {"request": {"attempt": 2}},
    }

    result = LogEventValidator().validate(event)

    assert result.valid is True
    assert result.errors == []
    assert result.mode == "LENIENT"


def test_validator_reports_missing_required_field_without_exposing_event_values() -> None:
    event = {
        "timestamp": "2026-08-13T12:00:00+00:00",
        "severity": "INFO",
        "message": "contains-secret-value",
        "event.name": "TEST_EVENT",
        "schema.version": "1.0.0",
        "sdk.name": "dt3-python",
        "sdk.version": "0.1.0",
        "service.name": "test-service",
        "service.version": "1.0.0",
    }

    result = LogEventValidator().validate(event)

    assert result.valid is False
    assert result.errors == [
        "required property is missing (deployment.environment)"
    ]
    assert all("contains-secret-value" not in error for error in result.errors)


def test_validator_reports_invalid_types_and_nested_structure() -> None:
    event = {
        "timestamp": "2026-08-13T12:00:00+00:00",
        "severity": "INFO",
        "message": "Test",
        "event.name": "TEST_EVENT",
        "schema.version": "1.0.0",
        "sdk.name": "dt3-python",
        "sdk.version": "0.1.0",
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "duration.ms": -1,
        "error.retryable": "yes",
        "attributes": ["not-an-object"],
    }

    result = LogEventValidator().validate(event)

    assert result.valid is False
    assert any("duration.ms" in error for error in result.errors)
    assert any("error.retryable" in error for error in result.errors)
    assert any("attributes" in error for error in result.errors)


def test_strict_mode_raises_for_invalid_log_event_and_does_not_export(capsys) -> None:
    logger = create_logger(_config("STRICT"))

    with pytest.raises(ValidationError) as error:
        logger.info("Invalid event", {"event.name": "not-valid"})

    assert "event.name" in str(error.value)
    assert capsys.readouterr().out == ""


def test_lenient_mode_exports_invalid_event_with_sanitized_validation_errors(capsys) -> None:
    logger = create_logger(_config("LENIENT"))

    logger.info(
        "Invalid event",
        {
            "event.name": "not-valid",
            "password": "top-secret",
            "attributes": {"nested": {"source": "caller"}},
        },
    )

    exported_event = json.loads(capsys.readouterr().out)
    assert exported_event["password"] == "[REDACTED]"
    assert "dt3.validation.errors" in exported_event
    assert any("event.name" in error for error in exported_event["dt3.validation.errors"])
    assert all("top-secret" not in error for error in exported_event["dt3.validation.errors"])


def test_lenient_mode_reports_missing_deployment_environment(capsys) -> None:
    logger = create_logger(
        _config("LENIENT", **{"deployment.environment": None})
    )
    del logger.config["deployment.environment"]

    logger.info("Missing deployment metadata", {"event.name": "VALID_EVENT"})

    exported_event = json.loads(capsys.readouterr().out)
    assert "deployment.environment" not in exported_event
    assert exported_event["dt3.validation.errors"] == [
        "required property is missing (deployment.environment)"
    ]


def test_default_mode_reports_missing_deployment_environment(capsys) -> None:
    config = _config(**{"deployment.environment": None})
    del config["deployment.environment"]
    logger = create_logger(config)

    logger.info("Missing deployment metadata", {"event.name": "VALID_EVENT"})

    exported_event = json.loads(capsys.readouterr().out)
    assert logger.validation_mode == "LENIENT"
    assert "deployment.environment" not in exported_event
    assert any(
        "deployment.environment" in error
        for error in exported_event["dt3.validation.errors"]
    )


def test_strict_mode_rejects_missing_deployment_environment(capsys) -> None:
    config = _config("STRICT", **{"deployment.environment": None})
    del config["deployment.environment"]
    logger = create_logger(config)

    with pytest.raises(
        ValidationError,
        match=r"required property is missing \(deployment\.environment\)",
    ):
        logger.info("Missing deployment metadata", {"event.name": "VALID_EVENT"})

    assert capsys.readouterr().out == ""


def test_off_mode_skips_validation_and_does_not_attach_errors(capsys) -> None:
    logger = create_logger(_config("OFF"))

    logger.info("Invalid event", {"event.name": "not-valid", "duration.ms": -1})

    exported_event = json.loads(capsys.readouterr().out)
    assert exported_event["event.name"] == "not-valid"
    assert "dt3.validation.errors" not in exported_event


def test_off_mode_emits_missing_deployment_environment_without_errors(capsys) -> None:
    config = _config("OFF", **{"deployment.environment": None})
    del config["deployment.environment"]
    logger = create_logger(config)

    logger.info("Missing deployment metadata", {"event.name": "VALID_EVENT"})

    exported_event = json.loads(capsys.readouterr().out)
    assert "deployment.environment" not in exported_event
    assert "dt3.validation.errors" not in exported_event


def test_validation_runs_after_masking_and_preserves_original_context(capsys) -> None:
    logger = create_logger(
        _config(
            "LENIENT",
            **{"masking.track_masked_fields": True},
        )
    )
    context = {
        "event.name": "INVALID_event",
        "tenant.id": "tenant-42",
        "attributes": {
            "credentials": {"token": "sensitive-token"},
            "request": {"id": "request-1"},
        },
    }

    logger.info("Validation with masked context", context)

    exported_event = json.loads(capsys.readouterr().out)
    assert exported_event["attributes"]["credentials"]["token"] == "[REDACTED]"
    assert exported_event["dt3.security.masked_fields"] == [
        "attributes.credentials.token"
    ]
    assert any("event.name" in error for error in exported_event["dt3.validation.errors"])
    assert context["attributes"]["credentials"]["token"] == "sensitive-token"
    assert context["attributes"]["request"]["id"] == "request-1"


def test_logger_exports_tenant_context_and_exception_error_fields(capsys) -> None:
    logger = create_logger(_config())

    try:
        raise RuntimeError("database unavailable")
    except RuntimeError as exception:
        logger.error(
            "Database request failed",
            exception,
            {
                "event.name": "DATABASE_REQUEST_FAILED",
                "tenant.id": "tenant-42",
                "tenant.environment": "production",
                "attributes": {"operation": "read"},
            },
        )

    exported_event = json.loads(capsys.readouterr().out)
    assert exported_event["tenant.id"] == "tenant-42"
    assert exported_event["tenant.environment"] == "production"
    assert exported_event["error.type"] == "RuntimeError"
    assert exported_event["error.message"] == "database unavailable"
    assert isinstance(exported_event["error.stack"], str)
    assert "dt3.validation.errors" not in exported_event
