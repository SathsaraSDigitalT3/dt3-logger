"""Public DT3 Commons Python SDK API."""

from .context import extract, inject, logger_context
from .emitter import EventEmitter
from .error_handler import Dt3ErrorReport, ErrorHandler
from .errors import (
    Dt3ConfigurationError,
    Dt3Error,
    Dt3ErrorCode,
    Dt3ErrorPhase,
    Dt3LifecycleError,
    Dt3MaskingError,
    Dt3SerializationError,
    Dt3TimeoutError,
    Dt3TransportError,
)
from .events import (
    build_ai_agent_execution,
    build_ai_event,
    build_ai_memory_retrieval,
    build_ai_prompt_submitted,
    build_ai_rag_retrieval,
    build_ai_request_event,
    build_ai_response_event,
    build_ai_response_received,
    build_ai_safety_filter_applied,
    build_ai_tool_invocation,
    build_api_event,
    build_db_event,
    build_messaging_event,
    wrap_log_event,
)
from .factory import create_logger
from .file_transport import FileTransport
from .http_transport import HttpTransport, HttpTransportError
from .kafka_transport import EventHubTransport, KafkaTransport, KafkaTransportError
from .masking import MaskingEngine
from .otlp_transport import OtlpTransport, OtlpTransportError
from .sink import EventSink, MultiSinkFanout, StdoutSink
from .timer import TimerImpl
from .tracer import Span, Tracer, create_tracer, generate_span_id, generate_trace_id
from .validation import (
    LogEventValidator,
    ValidationError,
    ValidationErrorDetail,
    ValidationResult,
)

__all__ = [
    "Dt3ConfigurationError",
    "Dt3Error",
    "Dt3ErrorCode",
    "Dt3ErrorPhase",
    "Dt3ErrorReport",
    "Dt3LifecycleError",
    "Dt3MaskingError",
    "Dt3SerializationError",
    "Dt3TimeoutError",
    "Dt3TransportError",
    "ErrorHandler",
    "EventEmitter",
    "EventHubTransport",
    "EventSink",
    "FileTransport",
    "HttpTransport",
    "HttpTransportError",
    "KafkaTransport",
    "KafkaTransportError",
    "LogEventValidator",
    "MaskingEngine",
    "MultiSinkFanout",
    "OtlpTransport",
    "OtlpTransportError",
    "Span",
    "StdoutSink",
    "TimerImpl",
    "Tracer",
    "ValidationError",
    "ValidationErrorDetail",
    "ValidationResult",
    "build_ai_agent_execution",
    "build_ai_event",
    "build_ai_memory_retrieval",
    "build_ai_prompt_submitted",
    "build_ai_rag_retrieval",
    "build_ai_request_event",
    "build_ai_response_event",
    "build_ai_response_received",
    "build_ai_safety_filter_applied",
    "build_ai_tool_invocation",
    "build_api_event",
    "build_db_event",
    "build_messaging_event",
    "create_logger",
    "create_tracer",
    "extract",
    "generate_span_id",
    "generate_trace_id",
    "inject",
    "logger_context",
    "wrap_log_event",
]
