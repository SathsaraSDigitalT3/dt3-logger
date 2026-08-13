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


def _assert_error_shape(error: object) -> None:
    """Assert that a validation error matches the canonical object contract."""
    assert isinstance(error, dict)
    assert set(error) == {"field", "message", "rule"}
    assert all(isinstance(value, str) for value in error.values())


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


@pytest.mark.parametrize(
    "timestamp",
    [
        "2026-08-13T12:00:00Z",
        "2026-08-13T12:00:00+00:00",
        "2026-08-13T12:00:00.123456-05:00",
    ],
)
def test_validator_accepts_rfc3339_date_time_values(timestamp: str) -> None:
    event = {
        "timestamp": timestamp,
        "severity": "INFO",
        "message": "Test",
        "event.name": "TEST_EVENT",
        "schema.version": "1.0.0",
        "sdk.name": "dt3-python",
        "sdk.version": "0.1.0",
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
    }

    result = LogEventValidator().validate(event)

    assert result.valid is True
    assert result.errors == []


def test_validator_reports_missing_required_field_as_sanitized_structured_detail() -> None:
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
    assert [detail.to_dict() for detail in result.errors] == [
        {
            "field": "deployment.environment",
            "message": "required property is missing",
            "rule": "required",
        }
    ]
    assert all(
        "contains-secret-value" not in detail.message for detail in result.errors
    )


def test_validator_reports_invalid_types_and_nested_structure_as_structured_details() -> None:
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
    errors_by_field = {detail.field: detail for detail in result.errors}

    assert result.valid is False
    assert errors_by_field["duration.ms"].rule == "minimum"
    assert errors_by_field["error.retryable"].rule == "type"
    assert errors_by_field["attributes"].rule == "type"
    assert all(
        detail.message == f"violates schema rule '{detail.rule}'"
        for detail in result.errors
    )


def test_validator_reports_malformed_timestamp_with_format_rule() -> None:
    event = {
        "timestamp": "not-a-date-time",
        "severity": "INFO",
        "message": "Test",
        "event.name": "TEST_EVENT",
        "schema.version": "1.0.0",
        "sdk.name": "dt3-python",
        "sdk.version": "0.1.0",
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
    }

    result = LogEventValidator().validate(event)

    assert result.valid is False
    assert [detail.to_dict() for detail in result.errors] == [
        {
            "field": "timestamp",
            "message": "violates schema rule 'format'",
            "rule": "format",
        }
    ]


@pytest.mark.parametrize(
    "timestamp",
    [
        "2026-08-13T12:00:00",
        "2026-08-13T12:00:00.123456",
        "2026-08-13",
        "not-a-date-time",
    ],
)
def test_validator_rejects_non_rfc3339_date_time_values(timestamp: str) -> None:
    event = {
        "timestamp": timestamp,
        "severity": "INFO",
        "message": "Test",
        "event.name": "TEST_EVENT",
        "schema.version": "1.0.0",
        "sdk.name": "dt3-python",
        "sdk.version": "0.1.0",
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
    }

    result = LogEventValidator().validate(event)

    assert result.valid is False
    assert [detail.to_dict() for detail in result.errors] == [
        {
            "field": "timestamp",
            "message": "violates schema rule 'format'",
            "rule": "format",
        }
    ]


def test_strict_mode_raises_for_invalid_log_event_and_does_not_export(capsys) -> None:
    logger = create_logger(_config("STRICT"))

    with pytest.raises(ValidationError) as error:
        logger.info("Invalid event", {"event.name": "not-valid"})

    assert "event.name" in str(error.value)
    assert "rule: pattern" in str(error.value)
    assert capsys.readouterr().out == ""


def test_lenient_mode_exports_invalid_event_with_sanitized_structured_validation_errors(
    capsys,
) -> None:
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
    validation_errors = exported_event["dt3.validation.errors"]
    assert all(_assert_error_shape(error) is None for error in validation_errors)
    assert any(error["field"] == "event.name" for error in validation_errors)
    assert all(
        "top-secret" not in error["message"] for error in validation_errors
    )


def test_lenient_mode_reports_missing_deployment_environment(capsys) -> None:
    logger = create_logger(
        _config("LENIENT", **{"deployment.environment": None})
    )
    del logger.config["deployment.environment"]

    logger.info("Missing deployment metadata", {"event.name": "VALID_EVENT"})

    exported_event = json.loads(capsys.readouterr().out)
    assert "deployment.environment" not in exported_event
    assert exported_event["dt3.validation.errors"] == [
        {
            "field": "deployment.environment",
            "message": "required property is missing",
            "rule": "required",
        }
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
        error["field"] == "deployment.environment"
        for error in exported_event["dt3.validation.errors"]
    )


def test_strict_mode_rejects_missing_deployment_environment(capsys) -> None:
    config = _config("STRICT", **{"deployment.environment": None})
    del config["deployment.environment"]
    logger = create_logger(config)

    with pytest.raises(
        ValidationError,
        match=r"deployment\.environment: required property is missing",
    ):
        logger.info("Missing deployment metadata", {"event.name": "VALID_EVENT"})

    assert capsys.readouterr().out == ""


def test_off_mode_skips_validation_and_does_not_attach_errors(capsys) -> None:
    logger = create_logger(_config("OFF"))

    logger.info("Invalid event", {"event.name": "not-valid", "duration.ms": -1})

    exported_event = json.loads(capsys.readouterr().out)
    assert exported_event["event.name"] == "not-valid"
    assert "dt3.validation.errors" not in exported_event


def test_off_mode_returns_success_without_evaluating_a_malformed_timestamp() -> None:
    result = LogEventValidator().validate(
        {"timestamp": "not-a-date-time"},
        mode="OFF",
    )

    assert result.valid is True
    assert result.errors == []
    assert result.mode == "OFF"


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
    assert any(
        error["field"] == "event.name"
        for error in exported_event["dt3.validation.errors"]
    )
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
