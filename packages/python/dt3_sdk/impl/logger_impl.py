"""Concrete stdout logger implementation for the DT3 Commons Python SDK."""

from __future__ import annotations

import json
import traceback
from datetime import datetime, timezone
from typing import Any, Dict, Optional

from dt3_sdk.masking import MaskingEngine


class LoggerImpl:
    """Build and export structured DT3 log events."""

    def __init__(self, config: Dict[str, Any]):
        """Initialize a logger from SDK configuration.

        Args:
            config: SDK configuration including service metadata and masking settings.
        """
        self.config = config
        self.exporter = config.get("exporter", "stdout")
        self.masking_engine = MaskingEngine(
            sensitive_fields=config.get("masking.fields"),
            replacement_value=config.get("masking.replacement_value", "[REDACTED]"),
            track_masked_fields=config.get("masking.track_masked_fields", False),
            enabled=config.get("masking.enabled", True),
        )

    def _log(
        self,
        level: str,
        message: str,
        context: Optional[Dict[str, Any]] = None,
        error: Optional[Exception] = None,
    ) -> None:
        """Create, mask, and export one structured log event."""
        context = context or {}
        event_name = context.get("event.name", "GENERIC_EVENT")

        log_event: Dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "severity": level,
            "message": message,
            "event.name": event_name,
            "schema.version": self.config.get("schema.version", "1.0.0"),
            "sdk.name": "dt3-python",
            "sdk.version": "0.1.0",
            "service.name": self.config.get("service.name", "unknown"),
            "service.version": self.config.get("service.version", "unknown"),
            "deployment.environment": self.config.get("deployment.environment", "unknown"),
        }

        # Context is copied into the event before recursive masking is applied.
        log_event.update(context)

        if error:
            log_event["error.type"] = type(error).__name__
            log_event["error.message"] = str(error)
            if error.__traceback__ is not None:
                log_event["error.stack"] = "".join(
                    traceback.format_exception(type(error), error, error.__traceback__)
                )

        masked_event, masked_fields = self.masking_engine.mask(log_event)
        if masked_fields:
            masked_event["dt3.security.masked_fields"] = masked_fields

        # Preserve existing exporter behavior: stdout is the only implemented exporter.
        if self.exporter == "stdout":
            print(json.dumps(masked_event))

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
    def error(
        self,
        message: str,
        error: Optional[Exception] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Export an ERROR log event with optional structured error details."""
        self._log("ERROR", message, context, error)

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Flush pending log events; stdout export has no pending buffer."""
        return None
