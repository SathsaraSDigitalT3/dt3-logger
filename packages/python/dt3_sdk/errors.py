"""Canonical error taxonomy for the DT3 Commons Python SDK."""

from __future__ import annotations

from enum import Enum
from typing import Any, Mapping, Optional


class Dt3ErrorCode(str, Enum):
    """Stable, machine-readable classification for SDK-internal failures."""

    CONFIGURATION_INVALID = "DT3_CONFIG_INVALID"
    EXPORTER_UNSUPPORTED = "DT3_EXPORTER_UNSUPPORTED"
    TRANSPORT_UNAVAILABLE = "DT3_TRANSPORT_UNAVAILABLE"
    TRANSPORT_TIMEOUT = "DT3_TRANSPORT_TIMEOUT"
    TRANSPORT_REJECTED = "DT3_TRANSPORT_REJECTED"
    TRANSPORT_CLOSED = "DT3_TRANSPORT_CLOSED"
    SERIALIZATION_FAILED = "DT3_SERIALIZATION_FAILED"
    MASKING_FAILED = "DT3_MASKING_FAILED"
    VALIDATION_FAILED = "DT3_VALIDATION_FAILED"
    BATCH_OVERFLOW = "DT3_BATCH_OVERFLOW"
    BATCH_ABORTED = "DT3_BATCH_ABORTED"
    LIFECYCLE_CLOSED = "DT3_LIFECYCLE_CLOSED"
    UNKNOWN = "DT3_UNKNOWN"


class Dt3ErrorPhase(str, Enum):
    """Pipeline stage that produced a failure."""

    CONFIGURATION = "configuration"
    ENRICHMENT = "enrichment"
    MASKING = "masking"
    VALIDATION = "validation"
    BATCHING = "batching"
    DELIVERY = "delivery"
    LIFECYCLE = "lifecycle"


class Dt3Error(RuntimeError):
    """Base class for all DT3 SDK-internal errors."""

    code: Dt3ErrorCode = Dt3ErrorCode.UNKNOWN
    retryable: bool = False
    phase: Dt3ErrorPhase = Dt3ErrorPhase.DELIVERY

    def __init__(
        self,
        message: str,
        *,
        code: Optional[Dt3ErrorCode] = None,
        retryable: Optional[bool] = None,
        phase: Optional[Dt3ErrorPhase] = None,
        details: Optional[Mapping[str, Any]] = None,
    ) -> None:
        """Initialize a classified SDK error."""
        super().__init__(message)
        if code is not None:
            self.code = code
        if retryable is not None:
            self.retryable = retryable
        if phase is not None:
            self.phase = phase
        self.details: dict[str, Any] = dict(details or {})

    # PUBLIC_INTERFACE
    def to_fields(self) -> dict[str, Any]:
        """Return canonical log-event error fields."""
        return {
            "error.type": type(self).__name__,
            "error.message": str(self),
            "error.code": self.code.value,
            "error.retryable": self.retryable,
        }


class Dt3ConfigurationError(Dt3Error, ValueError):
    """Invalid SDK configuration detected during logger construction."""

    code = Dt3ErrorCode.CONFIGURATION_INVALID
    retryable = False
    phase = Dt3ErrorPhase.CONFIGURATION


class Dt3TransportError(Dt3Error):
    """A transport could not deliver an event."""

    code = Dt3ErrorCode.TRANSPORT_UNAVAILABLE
    retryable = True
    phase = Dt3ErrorPhase.DELIVERY


class Dt3TimeoutError(Dt3TransportError):
    """A transport exceeded its configured request timeout."""

    code = Dt3ErrorCode.TRANSPORT_TIMEOUT
    retryable = True


class Dt3SerializationError(Dt3Error, TypeError):
    """A final event could not be serialized for export."""

    code = Dt3ErrorCode.SERIALIZATION_FAILED
    retryable = False
    phase = Dt3ErrorPhase.DELIVERY


class Dt3MaskingError(Dt3Error):
    """The masking engine could not process caller-provided context."""

    code = Dt3ErrorCode.MASKING_FAILED
    retryable = False
    phase = Dt3ErrorPhase.MASKING


class Dt3LifecycleError(Dt3Error):
    """An operation was attempted on a closed logger or transport."""

    code = Dt3ErrorCode.LIFECYCLE_CLOSED
    retryable = False
    phase = Dt3ErrorPhase.LIFECYCLE
