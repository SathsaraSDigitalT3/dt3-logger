"""Concrete logger implementation for the DT3 Commons Python SDK."""

from __future__ import annotations

import json
import traceback
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Mapping, Optional

from dt3_sdk.batching import EventBatcher
from dt3_sdk.context import ensure_correlation_id, get_active_logger_context
from dt3_sdk.file_transport import FileTransport
from dt3_sdk.http_transport import HttpTransport
from dt3_sdk.masking import MaskingEngine
from dt3_sdk.otlp_transport import OtlpTransport
from dt3_sdk.validation import LogEventValidator, ValidationError


class LoggerImpl:
    """Build, mask, validate, batch, and export structured DT3 log events."""

    def __init__(self, config: Dict[str, Any]):
        """Initialize a logger from canonical or legacy-compatible SDK configuration.

        Canonical exporter configuration keys take precedence over the supported
        legacy Python aliases. Public HTTP and OTLP timeout values are expressed
        in milliseconds and converted to seconds only at transport construction.

        Args:
            config: SDK configuration including service metadata, masking settings,
                validation mode, exporter selection, failure policy, batching, and
                exporter destination settings.

        Raises:
            ValueError: If configuration is invalid or an unsupported exporter is set.
            OSError: If the configured file destination cannot be opened.
        """
        self.config = dict(config)
        self.exporter = self.config.get("exporter", "stdout")
        self.validation_mode = str(
            self.config.get("validation.mode", "LENIENT")
        ).upper()
        self.fail_open = self._require_boolean(
            self.config.get("fail_open", True),
            "fail_open",
        )
        self.masking_engine = MaskingEngine(
            sensitive_fields=self.config.get("masking.fields"),
            replacement_value=self.config.get(
                "masking.replacement_value", "[REDACTED]"
            ),
            track_masked_fields=self.config.get(
                "masking.track_masked_fields", False
            ),
            enabled=self.config.get("masking.enabled", True),
        )
        self.validator = LogEventValidator()
        self._file_transport: Optional[FileTransport] = None
        self._http_transport: Optional[HttpTransport] = None
        self._otlp_transport: Optional[OtlpTransport] = None
        self._batcher: Optional[EventBatcher] = None
        self._closed = False
        self._auto_generate_correlation_id = self._require_boolean(
            self.config.get("tracing.auto_generate_correlation_id", False),
            "tracing.auto_generate_correlation_id",
        )

        if self.exporter == "file":
            self._file_transport = FileTransport(
                self._config_value("exporter.file.path", "file.path", default="")
            )
        elif self.exporter == "http":
            self._http_transport = HttpTransport(
                endpoint=self._config_value(
                    "exporter.http.endpoint",
                    "http.endpoint",
                    default="",
                ),
                timeout=self._http_timeout_seconds(),
                headers=self._config_value(
                    "exporter.http.headers",
                    "http.headers",
                    default=None,
                ),
            )
        elif self.exporter == "otlp":
            self._otlp_transport = OtlpTransport(
                endpoint=self._config_value("otlp.endpoint", default=""),
                timeout=self._otlp_timeout_seconds(),
                headers=self._config_value("otlp.headers", default=None),
            )
        elif self.exporter != "stdout":
            raise ValueError(f"Unsupported exporter: {self.exporter}")

        if self._require_boolean(
            self.config.get("batching.enabled", False),
            "batching.enabled",
        ):
            self._batcher = EventBatcher(
                self._export_with_policy,
                max_size=self.config.get("batching.max_size", 100),
                flush_interval_ms=self.config.get("batching.flush_interval_ms", 5000),
            )

    def _log(
        self,
        level: str,
        message: str,
        context: Optional[Dict[str, Any]] = None,
        error: Optional[Exception] = None,
    ) -> None:
        """Create, mask, validate, then batch or export one structured log event."""
        self._ensure_open()
        # ContextVars provide request/task-local state. Explicit per-event
        # context intentionally wins over active scope values, preserving the
        # logger's established caller-context precedence behavior.
        caller_context = ensure_correlation_id(
            get_active_logger_context(),
            auto_generate=self._auto_generate_correlation_id,
        )
        caller_context.update(context or {})
        event_name = caller_context.get("event.name")
        if not isinstance(event_name, str):
            event_name = "GENERIC_EVENT"

        # Context is merged first so the logger can reassert its reserved fields.
        # This prevents a caller from replacing method-owned severity, event metadata,
        # or the explicit error argument's structured error fields.
        log_event: Dict[str, Any] = dict(caller_context)
        log_event.update(
            {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "severity": level,
                "message": message,
                "event.name": event_name,
                "schema.version": self.config.get("schema.version", "1.0.0"),
                "sdk.name": self.config.get("sdk.name", "dt3-python"),
                "sdk.version": self.config.get("sdk.version", "0.1.0"),
                "service.name": self.config.get("service.name"),
                "service.version": self.config.get("service.version"),
            }
        )
        if "deployment.environment" in self.config:
            log_event["deployment.environment"] = self.config[
                "deployment.environment"
            ]

        if error is not None:
            log_event["error.type"] = type(error).__name__
            log_event["error.message"] = str(error)
            if error.__traceback__ is not None:
                log_event["error.stack"] = "".join(
                    traceback.format_exception(
                        type(error),
                        error,
                        error.__traceback__,
                    )
                )

        # The repository pipeline defines masking before validation, preventing
        # sensitive values from being exposed through validation handling.
        masked_event, masked_fields = self.masking_engine.mask(log_event)
        if masked_fields:
            masked_event["dt3.security.masked_fields"] = masked_fields

        validation_result = self.validator.validate(
            masked_event,
            mode=self.validation_mode,
        )
        if not validation_result.valid:
            if validation_result.mode == "STRICT":
                raise ValidationError(
                    "Log event failed schema validation: "
                    + "; ".join(
                        (
                            f"{detail.field}: {detail.message} "
                            f"(rule: {detail.rule})"
                        )
                        for detail in validation_result.errors
                    )
                )
            if validation_result.mode == "LENIENT":
                masked_event["dt3.validation.errors"] = [
                    detail.to_dict() for detail in validation_result.errors
                ]

        if self._batcher is not None:
            self._batcher.add(masked_event)
        else:
            self._export_with_policy(masked_event)

    def _export(self, event: Dict[str, Any]) -> None:
        """Deliver an already processed final event through the selected exporter."""
        if self.exporter == "stdout":
            print(json.dumps(event))
        elif self._file_transport is not None:
            self._file_transport.export(event)
        elif self._http_transport is not None:
            self._http_transport.export(event)
        elif self._otlp_transport is not None:
            self._otlp_transport.export(event)

    def _export_with_policy(self, event: Dict[str, Any]) -> None:
        """Deliver one final event under the configured delivery failure policy."""
        self._deliver(lambda: self._export(event))

    def _deliver(self, operation: Callable[[], None]) -> None:
        """Apply the configured delivery-only failure policy to one operation."""
        try:
            operation()
        except (OSError, RuntimeError, TypeError, ValueError):
            if not self.fail_open:
                raise

    def _config_value(
        self,
        canonical_key: str,
        legacy_key: Optional[str] = None,
        *,
        default: Any = None,
    ) -> Any:
        """Return a canonical configuration value with deterministic alias fallback."""
        if canonical_key in self.config:
            return self.config[canonical_key]
        if legacy_key is not None and legacy_key in self.config:
            return self.config[legacy_key]
        return default

    def _http_timeout_seconds(self) -> float:
        """Resolve canonical millisecond and legacy second HTTP timeout values."""
        if "exporter.http.timeout" in self.config:
            return self._timeout_seconds(
                self.config["exporter.http.timeout"],
                "exporter.http.timeout",
            )
        if "http.timeout" in self.config:
            return self._legacy_timeout_seconds(
                self.config["http.timeout"],
                "http.timeout",
            )
        return self._timeout_seconds(5000, "exporter.http.timeout")

    def _otlp_timeout_seconds(self) -> float:
        """Resolve canonical millisecond and legacy second OTLP timeout values."""
        if "exporter.otlp.timeout" in self.config:
            return self._timeout_seconds(
                self.config["exporter.otlp.timeout"],
                "exporter.otlp.timeout",
            )
        if "otlp.timeout" in self.config:
            return self._legacy_timeout_seconds(
                self.config["otlp.timeout"],
                "otlp.timeout",
            )
        return self._timeout_seconds(10000, "exporter.otlp.timeout")

    @staticmethod
    def _timeout_seconds(value: Any, key: str) -> float:
        """Validate a millisecond timeout and convert it for urllib."""
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"{key} must be a positive timeout in milliseconds")
        if value <= 0:
            raise ValueError(f"{key} must be greater than zero")
        return float(value) / 1000

    @staticmethod
    def _legacy_timeout_seconds(value: Any, key: str) -> float:
        """Validate a legacy seconds timeout without changing its historical unit."""
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"{key} must be a positive timeout in seconds")
        if value <= 0:
            raise ValueError(f"{key} must be greater than zero")
        return float(value)

    def _ensure_open(self) -> None:
        """Fail lifecycle operations deterministically after the logger closes."""
        if self._closed:
            raise RuntimeError("Logger is closed")

    @staticmethod
    def _require_boolean(value: Any, key: str) -> bool:
        """Validate a canonical boolean configuration value."""
        if not isinstance(value, bool):
            raise ValueError(f"{key} must be a boolean")
        return value

    # PUBLIC_INTERFACE
    def debug(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Export a DEBUG log event."""
        self._log("DEBUG", message, context)

    # PUBLIC_INTERFACE
    def info(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Export an INFO log event."""
        self._log("INFO", message, context)

    # PUBLIC_INTERFACE
    def warn(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Export a WARN log event."""
        self._log("WARN", message, context)

    # PUBLIC_INTERFACE
    def fatal(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Export a FATAL log event through the canonical processing pipeline."""
        self._log("FATAL", message, context)

    # PUBLIC_INTERFACE
    def error(
        self,
        message: str,
        error: Optional[Exception] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Export an ERROR log event with optional structured error details."""
        self._log("ERROR", message, context, error)

    # PUBLIC_INTERFACE
    def event(self, event_object: Mapping[str, Any]) -> None:
        """Process a canonical log event through masking, validation, and export.

        Method-owned fields remain authoritative for event construction. The
        supplied severity determines the logging method pipeline used, while
        all other event fields are preserved as caller context.

        Args:
            event_object: Canonical event fields, including a string message and
                optional severity. The mapping is never mutated.

        Raises:
            TypeError: If the supplied event is not a mapping or message is not a string.
            ValueError: If its severity is unsupported.
            RuntimeError: If the logger is closed.
        """
        if not isinstance(event_object, Mapping):
            raise TypeError("event_object must be a mapping")

        supplied_event = dict(event_object)
        message = supplied_event.pop("message", "")
        if not isinstance(message, str):
            raise TypeError("event_object.message must be a string")

        severity = str(supplied_event.pop("severity", "INFO")).upper()
        if severity not in {"DEBUG", "INFO", "WARN", "ERROR", "FATAL"}:
            raise ValueError("event_object.severity must be a supported severity")
        self._log(severity, message, supplied_event)

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Synchronously flush buffered events and the selected exporter."""
        self._ensure_open()
        if self._batcher is not None:
            self._batcher.flush()

        if self._file_transport is not None:
            self._deliver(self._file_transport.flush)
        elif self._http_transport is not None:
            self._deliver(self._http_transport.flush)
        elif self._otlp_transport is not None:
            self._deliver(self._otlp_transport.flush)

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Flush remaining events, then close the selected exporter."""
        if self._closed:
            return
        self._closed = True

        if self._batcher is not None:
            self._deliver(self._batcher.close)

        if self._file_transport is not None:
            self._deliver(self._file_transport.close)
        elif self._http_transport is not None:
            self._deliver(self._http_transport.close)
        elif self._otlp_transport is not None:
            self._deliver(self._otlp_transport.close)

    # PUBLIC_INTERFACE
    def create_timer(
        self,
        name: str,
        context: Optional[Dict[str, Any]] = None,
    ) -> "TimerImpl":
        """Create an unstarted Timer that emits a canonical completion event.

        Args:
            name: Non-empty canonical event name for the completion event.
            context: Optional metadata merged with active logger context at finish.

        Returns:
            A new unstarted TimerImpl instance.

        Raises:
            RuntimeError: If the logger is closed.
            TypeError: If the name or context has an unsupported type.
            ValueError: If the name is blank.
        """
        from dt3_sdk.timer import TimerImpl

        self._ensure_open()
        return TimerImpl(self, name, context)
