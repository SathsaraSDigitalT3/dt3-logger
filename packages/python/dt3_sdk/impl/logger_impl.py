"""Concrete logger implementation for the DT3 Commons Python SDK."""

from __future__ import annotations

import traceback
import uuid
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any, Callable, Dict, List, Mapping, Optional, Sequence, Tuple

from dt3_sdk.batching import EventBatcher
from dt3_sdk.context import ensure_correlation_id, get_active_logger_context
from dt3_sdk.error_handler import ErrorHandler
from dt3_sdk.errors import Dt3Error, Dt3ErrorPhase, Dt3MaskingError
from dt3_sdk.file_transport import FileTransport
from dt3_sdk.http_transport import HttpTransport
from dt3_sdk.masking import MaskingEngine
from dt3_sdk.otlp_transport import OtlpTransport
from dt3_sdk.sink import EventSink, MultiSinkFanout, StdoutSink
from dt3_sdk.validation import LogEventValidator, ValidationError

if TYPE_CHECKING:
    from dt3_sdk.timer import TimerImpl
    from dt3_sdk.tracer import Tracer


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
        construction_handler = ErrorHandler(
            fail_open=True,
            diagnostics_enabled=bool(
                self.config.get("error.diagnostics.enabled", True)
            ),
            include_stack=bool(self.config.get("error.include_stack", False)),
            # Invalid rate-limit configuration must not prevent reporting the
            # original construction failure.
            rate_limit_per_minute=20,
            on_error=self.config.get("error.on_error"),
        )
        try:
            self._initialize()
        except BaseException as error:
            construction_handler.report(
                error,
                phase=Dt3ErrorPhase.CONFIGURATION,
                context={"exporter": self._safe_exporter_name()},
            )
            raise

    def _safe_exporter_name(self) -> str:
        """Return a safe exporter diagnostic without invoking arbitrary __str__ methods."""
        exporters = self.config.get("exporters")
        if isinstance(exporters, list) and exporters:
            names = [name for name in exporters if isinstance(name, str)]
            if names:
                return ",".join(names)
        exporter = self.config.get("exporter", "stdout")
        return exporter if isinstance(exporter, str) else "invalid"

    def _initialize(self) -> None:
        """Initialize logger pipeline components after construction reporting is ready."""
        self.exporter = self.config.get("exporter", "stdout")
        exporters = self.config.get("exporters")
        if exporters is not None:
            if not isinstance(exporters, list) or not exporters:
                from dt3_sdk.errors import Dt3ConfigurationError

                raise Dt3ConfigurationError("exporters must be a non-empty list")
            if any(not isinstance(name, str) for name in exporters):
                from dt3_sdk.errors import Dt3ConfigurationError

                raise Dt3ConfigurationError("exporters entries must be strings")
            self.exporter = ",".join(exporters)
        elif not isinstance(self.exporter, str):
            from dt3_sdk.errors import Dt3ConfigurationError

            raise Dt3ConfigurationError("Unsupported exporter type")

        self.validation_mode = str(
            self.config.get("validation.mode", "LENIENT")
        ).upper()
        self.fail_open = self._require_boolean(
            self.config.get("fail_open", True),
            "fail_open",
        )
        self.error_handler = ErrorHandler(
            fail_open=self.fail_open,
            diagnostics_enabled=self._require_boolean(
                self.config.get("error.diagnostics.enabled", True),
                "error.diagnostics.enabled",
            ),
            include_stack=self._require_boolean(
                self.config.get("error.include_stack", False),
                "error.include_stack",
            ),
            rate_limit_per_minute=self.config.get(
                "error.rate_limit_per_minute",
                20,
            ),
            on_error=self.config.get("error.on_error"),
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
        self._span_events_enabled = self._require_boolean(
            self.config.get("tracing.span_events.enabled", True),
            "tracing.span_events.enabled",
        )
        self._auto_generate_ids = self._require_boolean(
            self.config.get("tracing.auto_generate_ids", True),
            "tracing.auto_generate_ids",
        )

        sink_pairs = self._build_sink_pairs(exporters)
        self._fanout = MultiSinkFanout(
            sink_pairs,
            on_error=self._on_sink_error,
        )

        if self._require_boolean(
            self.config.get("batching.enabled", False),
            "batching.enabled",
        ):
            self._batcher = EventBatcher(
                self._export_with_policy,
                max_size=self.config.get("batching.max_size", 100),
                flush_interval_ms=self.config.get("batching.flush_interval_ms", 5000),
                on_error=self._handle_batching_error,
            )

    def _build_sink_pairs(
        self,
        exporters: Optional[List[str]],
    ) -> List[Tuple[str, EventSink]]:
        """Build the initial ``(name, sink)`` list from config."""
        pairs: List[Tuple[str, EventSink]] = []

        if exporters is not None:
            for name in exporters:
                pairs.append((name, self._create_builtin_sink(name)))
        else:
            pairs.append((self.exporter, self._create_builtin_sink(self.exporter)))

        configured_sinks = self.config.get("sinks")
        if configured_sinks is not None:
            if not isinstance(configured_sinks, Sequence) or isinstance(
                configured_sinks, (str, bytes)
            ):
                from dt3_sdk.errors import Dt3ConfigurationError

                raise Dt3ConfigurationError("sinks must be a list of EventSink instances")
            for index, sink in enumerate(configured_sinks):
                if not self._looks_like_sink(sink):
                    from dt3_sdk.errors import Dt3ConfigurationError

                    raise Dt3ConfigurationError(
                        "sinks entries must implement export/flush/close"
                    )
                pairs.append((f"custom-{index}", sink))

        return pairs

    def _create_builtin_sink(self, exporter_name: str) -> EventSink:
        """Construct a built-in sink for a named exporter."""
        if exporter_name == "stdout":
            return StdoutSink()
        if exporter_name == "file":
            transport = FileTransport(
                self._config_value("exporter.file.path", "file.path", default="")
            )
            self._file_transport = transport
            return transport
        if exporter_name == "http":
            transport = HttpTransport(
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
            self._http_transport = transport
            return transport
        if exporter_name == "otlp":
            transport = OtlpTransport(
                endpoint=self._config_value("otlp.endpoint", default=""),
                timeout=self._otlp_timeout_seconds(),
                headers=self._config_value("otlp.headers", default=None),
            )
            self._otlp_transport = transport
            return transport
        if exporter_name == "kafka":
            from dt3_sdk.kafka_transport import KafkaTransport

            timeout_ms = self._config_value("exporter.kafka.timeout", default=10000)
            transport = KafkaTransport(
                topic=str(self._config_value("exporter.kafka.topic", default="") or ""),
                rest_endpoint=str(
                    self._config_value("exporter.kafka.rest_endpoint", default="") or ""
                ),
                timeout=self._timeout_seconds(timeout_ms, "exporter.kafka.timeout"),
                headers=self._config_value("exporter.kafka.headers", default=None),
            )
            return transport
        if exporter_name == "eventhub":
            from dt3_sdk.kafka_transport import EventHubTransport

            timeout_ms = self._config_value("exporter.eventhub.timeout", default=10000)
            transport = EventHubTransport(
                endpoint=str(
                    self._config_value("exporter.eventhub.endpoint", default="") or ""
                ),
                timeout=self._timeout_seconds(timeout_ms, "exporter.eventhub.timeout"),
                headers=self._config_value("exporter.eventhub.headers", default=None),
            )
            return transport

        from dt3_sdk.errors import Dt3ConfigurationError

        raise Dt3ConfigurationError(f"Unsupported exporter: {exporter_name}")

    @staticmethod
    def _looks_like_sink(sink: Any) -> bool:
        """Return whether an object exposes the EventSink method surface."""
        return all(
            callable(getattr(sink, method_name, None))
            for method_name in ("export", "flush", "close")
        )

    def _on_sink_error(self, sink_name: str, error: BaseException) -> None:
        """Report a per-sink failure and apply fail-open disposition."""
        self.error_handler.handle(
            error,
            phase=Dt3ErrorPhase.DELIVERY,
            context={"exporter": sink_name, "sink": sink_name},
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
        # operation.id from caller/scoped context passes through via this merge.
        log_event: Dict[str, Any] = dict(caller_context)
        log_event.update(
            {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "severity": level,
                "message": message,
                "event.name": event_name,
                "schema.version": self.config.get("schema.version", "1.1.0"),
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

        event_id = log_event.get("event.id")
        if not isinstance(event_id, str) or not event_id.strip():
            log_event["event.id"] = str(uuid.uuid4())

        if self._auto_generate_ids:
            from dt3_sdk.tracer import generate_span_id, generate_trace_id

            trace_id = log_event.get("trace.id")
            if not isinstance(trace_id, str) or len(trace_id) != 32:
                log_event["trace.id"] = generate_trace_id()
            span_id = log_event.get("span.id")
            if not isinstance(span_id, str) or len(span_id) != 16:
                log_event["span.id"] = generate_span_id()

        component_name = log_event.get("component.name")
        if not isinstance(component_name, str) or not component_name.strip():
            configured_component = self.config.get("component.name")
            if isinstance(configured_component, str) and configured_component.strip():
                log_event["component.name"] = configured_component

        if error is not None:
            log_event["error.type"] = type(error).__name__
            log_event["error.message"] = str(error)
            if isinstance(error, Dt3Error):
                log_event["error.code"] = error.code.value
                log_event["error.retryable"] = error.retryable
            else:
                code = getattr(error, "code", None)
                if isinstance(code, str):
                    log_event["error.code"] = code
                retryable = getattr(error, "retryable", None)
                if isinstance(retryable, bool):
                    log_event["error.retryable"] = retryable
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
        try:
            masked_event, masked_fields = self.masking_engine.mask(log_event)
        except RecursionError as masking_error:
            handled_error = Dt3MaskingError(
                "masking failed for the supplied context"
            ).with_traceback(masking_error.__traceback__)
            self.error_handler.handle(
                handled_error,
                phase=Dt3ErrorPhase.MASKING,
                context={"exporter": self.exporter},
            )
            return
        if masked_fields:
            masked_event["dt3.security.masked_fields"] = masked_fields

        validation_result = self.validator.validate(
            masked_event,
            mode=self.validation_mode,
        )
        if not validation_result.valid:
            if validation_result.mode == "STRICT":
                validation_error = ValidationError(
                    "Log event failed schema validation: "
                    + "; ".join(
                        (
                            f"{detail.field}: {detail.message} "
                            f"(rule: {detail.rule})"
                        )
                        for detail in validation_result.errors
                    )
                )
                # Strict validation is intentionally fail-closed regardless of
                # delivery's fail_open setting, but is still observable.
                self.error_handler.report(
                    validation_error,
                    phase=Dt3ErrorPhase.VALIDATION,
                    context={"mode": "STRICT"},
                )
                raise validation_error
            if validation_result.mode == "LENIENT":
                masked_event["dt3.validation.errors"] = [
                    detail.to_dict() for detail in validation_result.errors
                ]

        if self._batcher is not None:
            self._batcher.add(masked_event)
        else:
            self._export_with_policy(masked_event)

    def _export(self, event: Dict[str, Any]) -> None:
        """Deliver an already processed final event through configured sinks."""
        self._fanout.export(event)

    def _export_with_policy(self, event: Dict[str, Any]) -> None:
        """Deliver one final event under the configured delivery failure policy.

        Per-sink failures are reported inside MultiSinkFanout via ``_on_sink_error``.
        The fan-out re-raises disposition errors after all sinks have been attempted.
        """
        self._fanout.export(event)

    def _deliver(
        self,
        operation: Callable[[], None],
        *,
        phase: Dt3ErrorPhase = Dt3ErrorPhase.DELIVERY,
    ) -> None:
        """Apply centralized classification and fail-open policy to an operation."""
        self.error_handler.guard(
            operation,
            phase=phase,
            context={"exporter": self.exporter},
        )

    def _handle_batching_error(
        self,
        error: BaseException,
        phase: Dt3ErrorPhase,
    ) -> None:
        """Report timer and terminal batching failures without rethrowing."""
        try:
            self.error_handler.handle(
                error,
                phase=phase,
                context={"exporter": self.exporter},
            )
        except BaseException:
            # Timer-thread errors must never escape the batching thread.
            pass

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
            from dt3_sdk.errors import Dt3LifecycleError

            error = Dt3LifecycleError("Logger is closed")
            self.error_handler.report(
                error,
                phase=Dt3ErrorPhase.LIFECYCLE,
                context={"exporter": self.exporter},
            )
            raise error

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
    def register_sink(self, sink: EventSink, name: Optional[str] = None) -> str:
        """Register an additional sink for runtime fan-out.

        Args:
            sink: Object implementing ``export`` / ``flush`` / ``close``.
            name: Optional diagnostic name for the sink.

        Returns:
            The assigned sink name.

        Raises:
            TypeError: If the sink does not implement the EventSink surface.
            RuntimeError: If the logger is closed.
        """
        self._ensure_open()
        if not self._looks_like_sink(sink):
            raise TypeError("sink must implement export, flush, and close")
        return self._fanout.register(sink, name)

    # PUBLIC_INTERFACE
    def create_tracer(self) -> "Tracer":
        """Create a Tracer bound to this logger using configured span-event settings."""
        from dt3_sdk.tracer import Tracer

        self._ensure_open()
        return Tracer(self, span_events_enabled=self._span_events_enabled)

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Synchronously flush buffered events and all configured sinks."""
        self._ensure_open()
        if self._batcher is not None:
            self._batcher.flush()
        self._fanout.flush()

    # PUBLIC_INTERFACE
    def error_snapshot(self) -> Dict[str, int]:
        """Return cumulative SDK-internal error counts keyed by DT3 error code."""
        return self.error_handler.snapshot()

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Flush remaining events, then close all configured sinks."""
        if self._closed:
            return
        self._closed = True

        if self._batcher is not None:
            self._deliver(self._batcher.close)

        # Per-sink failures are reported via ``_on_sink_error``; disposition
        # errors (fail_open=False) are re-raised after all sinks are attempted.
        self._fanout.close()

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
