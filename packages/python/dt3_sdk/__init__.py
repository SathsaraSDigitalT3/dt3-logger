"""Public DT3 Commons Python SDK API."""

from .context import extract, inject, logger_context
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
from .factory import create_logger
from .file_transport import FileTransport
from .http_transport import HttpTransport, HttpTransportError
from .masking import MaskingEngine
from .otlp_transport import OtlpTransport, OtlpTransportError
from .timer import TimerImpl
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
    "FileTransport",
    "HttpTransport",
    "HttpTransportError",
    "LogEventValidator",
    "MaskingEngine",
    "OtlpTransport",
    "OtlpTransportError",
    "TimerImpl",
    "ValidationError",
    "ValidationErrorDetail",
    "ValidationResult",
    "create_logger",
    "extract",
    "inject",
    "logger_context",
]
