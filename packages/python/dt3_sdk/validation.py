"""Runtime validation for canonical DT3 structured log events."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Mapping

from jsonschema import Draft202012Validator, FormatChecker


class ValidationError(ValueError):
    """Raised when a log event fails validation in STRICT mode."""


@dataclass(frozen=True)
class ValidationResult:
    """The sanitized outcome of validating one log event."""

    valid: bool
    errors: List[str]
    mode: str


class LogEventValidator:
    """Validate log events using the repository's canonical JSON Schema."""

    def __init__(self) -> None:
        """Load and compile the canonical ``log-event.schema.json`` schema."""
        schema_path = Path(__file__).resolve().parents[3] / "schemas" / "log-event.schema.json"
        with schema_path.open(encoding="utf-8") as schema_file:
            schema = json.load(schema_file)

        self._validator = Draft202012Validator(schema, format_checker=FormatChecker())

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
            self._format_error(error)
            for error in sorted(self._validator.iter_errors(dict(event)), key=self._error_sort_key)
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
    def _format_error(error: Any) -> str:
        """Format an error without including caller-supplied values."""
        if error.validator == "required":
            # jsonschema exposes the complete required-field list in
            # ``validator_value``. Its message identifies the one field that is
            # actually absent, which is the only schema detail we should report.
            missing_field = error.message.split("'", 2)[1]
            return f"required property is missing ({missing_field})"

        field = ".".join(str(part) for part in error.absolute_path) or "$"
        return f"{field}: violates schema rule '{error.validator}'"
