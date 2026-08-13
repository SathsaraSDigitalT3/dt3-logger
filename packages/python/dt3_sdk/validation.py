"""Runtime validation for canonical DT3 structured log events."""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Mapping

from jsonschema import Draft202012Validator, FormatChecker


_FORMAT_CHECKER = FormatChecker()


@_FORMAT_CHECKER.checks("date-time")
def _is_rfc3339_date_time(value: object) -> bool:
    """Return whether a string is a valid RFC 3339 date-time value.

    Python's built-in ISO parser accepts a broader range of values than the
    JSON Schema ``date-time`` format. The canonical DT3 schema requires the
    RFC 3339 profile, including a timezone offset.
    """
    if not isinstance(value, str):
        return True

    normalized_value = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed_value = datetime.fromisoformat(normalized_value)
    except ValueError:
        return False

    return parsed_value.tzinfo is not None


class ValidationError(ValueError):
    """Raised when a log event fails validation in STRICT mode."""


@dataclass(frozen=True)
class ValidationErrorDetail:
    """A sanitized JSON Schema validation failure matching the canonical contract."""

    field: str
    message: str
    rule: str

    def to_dict(self) -> Dict[str, str]:
        """Return this detail as a JSON-serializable validation error object."""
        return asdict(self)


@dataclass(frozen=True)
class ValidationResult:
    """The sanitized outcome of validating one log event."""

    valid: bool
    errors: List[ValidationErrorDetail]
    mode: str


class LogEventValidator:
    """Validate log events using the repository's canonical JSON Schema."""

    def __init__(self) -> None:
        """Load and compile the canonical ``log-event.schema.json`` schema."""
        schema_path = Path(__file__).resolve().parents[3] / "schemas" / "log-event.schema.json"
        with schema_path.open(encoding="utf-8") as schema_file:
            schema = json.load(schema_file)

        self._validator = Draft202012Validator(
            schema,
            format_checker=_FORMAT_CHECKER,
        )

    # PUBLIC_INTERFACE
    def validate(self, event: Mapping[str, Any], mode: str = "LENIENT") -> ValidationResult:
        """Validate a structured log event against the canonical repository schema.

        Args:
            event: Event fields to validate. The mapping is never mutated.
            mode: The configured validation mode: ``STRICT``, ``LENIENT``, or ``OFF``.

        Returns:
            A validation result containing only schema-rule details, never input values.

        Raises:
            ValueError: If ``mode`` is not one of the repository-defined modes.
        """
        normalized_mode = mode.upper()
        if normalized_mode not in {"STRICT", "LENIENT", "OFF"}:
            raise ValueError(
                "validation.mode must be one of STRICT, LENIENT, or OFF"
            )

        if normalized_mode == "OFF":
            return ValidationResult(valid=True, errors=[], mode=normalized_mode)

        errors = [
            self._build_error_detail(error)
            for error in sorted(
                self._validator.iter_errors(dict(event)),
                key=self._error_sort_key,
            )
        ]
        return ValidationResult(
            valid=not errors,
            errors=errors,
            mode=normalized_mode,
        )

    @staticmethod
    def _error_sort_key(error: Any) -> tuple[str, str]:
        """Produce stable ordering for JSON Schema errors."""
        path = ".".join(str(part) for part in error.absolute_path)
        return path, error.validator

    @staticmethod
    def _build_error_detail(error: Any) -> ValidationErrorDetail:
        """Build a sanitized error detail without including caller-supplied values."""
        rule = str(error.validator)

        if rule == "required":
            # The schema engine's message identifies the missing property but does
            # not require exposing any value supplied by the caller.
            match = re.search(r"'([^']+)' is a required property", error.message)
            missing_field = match.group(1) if match else "$"
            return ValidationErrorDetail(
                field=missing_field,
                message="required property is missing",
                rule=rule,
            )

        field = ".".join(str(part) for part in error.absolute_path) or "$"
        return ValidationErrorDetail(
            field=field,
            message=f"violates schema rule '{rule}'",
            rule=rule,
        )
