"""Synchronous OTLP/HTTP JSON transport for final DT3 structured log events."""

from __future__ import annotations

import json
import threading
from datetime import datetime, timezone
from typing import Any, Mapping, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .errors import Dt3ErrorCode, Dt3TransportError


class OtlpTransportError(Dt3TransportError):
    """Raised when the OTLP transport cannot successfully export an event."""

    code = Dt3ErrorCode.TRANSPORT_UNAVAILABLE
    retryable = True


class OtlpTransport:
    """Synchronously export final DT3 log events through OTLP/HTTP JSON."""

    _SEVERITY_NUMBERS = {
        "TRACE": 1,
        "DEBUG": 5,
        "INFO": 9,
        "WARN": 13,
        "WARNING": 13,
        "ERROR": 17,
        "FATAL": 21,
    }

    def __init__(
        self,
        endpoint: str,
        timeout: float = 10.0,
        headers: Optional[Mapping[str, str]] = None,
    ) -> None:
        """Create an OTLP/HTTP JSON transport.

        Args:
            endpoint: OTLP Logs HTTP endpoint, commonly ending in ``/v1/logs``.
            timeout: Maximum number of seconds allowed for an export request.
            headers: Optional request headers merged with OTLP JSON content type.

        Raises:
            ValueError: If the endpoint, timeout, or configured headers are invalid.
        """
        if not isinstance(endpoint, str) or not endpoint.strip():
            raise ValueError("otlp.endpoint must be configured for the OTLP exporter")
        if timeout <= 0:
            raise ValueError("otlp.timeout must be greater than zero")
        if headers is not None and not isinstance(headers, Mapping):
            raise ValueError(
                "otlp.headers must be a mapping of string header names to string values"
            )

        validated_headers: dict[str, str] = {}
        for name, value in (headers or {}).items():
            if not isinstance(name, str) or not name.strip():
                raise ValueError(
                    f"otlp.headers contains an invalid header name: {name!r}"
                )
            if "\r" in name or "\n" in name:
                raise ValueError("otlp.headers contains an invalid header name")
            if not isinstance(value, str):
                raise ValueError(
                    f"otlp.headers[{name!r}] must have a string header value; got {value!r}"
                )
            if "\r" in value or "\n" in value:
                raise ValueError(f"otlp.headers[{name!r}] contains an invalid header value")
            validated_headers[name] = value

        self._endpoint = endpoint.strip()
        self._timeout = timeout
        self._headers = validated_headers
        self._lock = threading.Lock()
        self._closed = False

    # PUBLIC_INTERFACE
    def export(self, event: Mapping[str, Any]) -> None:
        """Synchronously export one final DT3 event as an OTLP Logs JSON payload.

        Args:
            event: The already-masked and validation-processed canonical log event.

        Raises:
            RuntimeError: If the transport has already been closed.
            OtlpTransportError: If the request fails or receives a non-2xx status.
            TypeError: If the final event cannot be JSON serialized.
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("OTLP transport is closed")

            payload = json.dumps(
                self.to_otlp_payload(event),
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
            headers = {
                name: value
                for name, value in self._headers.items()
                if name.lower() != "content-type"
            }
            headers["Content-Type"] = "application/json"
            request = Request(
                self._endpoint,
                data=payload,
                headers=headers,
                method="POST",
            )

            try:
                with urlopen(request, timeout=self._timeout) as response:
                    status = response.getcode()
                    if status < 200 or status >= 300:
                        raise OtlpTransportError(
                            f"OTLP export failed with status {status}",
                            code=Dt3ErrorCode.TRANSPORT_REJECTED,
                            retryable=status >= 500,
                        )
            except HTTPError as error:
                raise OtlpTransportError(
                    f"OTLP export failed with status {error.code}",
                    code=Dt3ErrorCode.TRANSPORT_REJECTED,
                    retryable=error.code >= 500,
                ) from error
            except URLError as error:
                reason = str(getattr(error, "reason", "request failed"))
                raise OtlpTransportError(
                    f"OTLP export request failed: {reason}"
                ) from error
            except TimeoutError as error:
                raise OtlpTransportError(
                    "OTLP export request timed out",
                    code=Dt3ErrorCode.TRANSPORT_TIMEOUT,
                    retryable=True,
                ) from error

    # PUBLIC_INTERFACE
    @classmethod
    def to_otlp_payload(cls, event: Mapping[str, Any]) -> dict[str, Any]:
        """Map one final DT3 LogEvent into an OTLP Logs JSON export request.

        Args:
            event: The final canonical DT3 log event.

        Returns:
            A standards-shaped OTLP/HTTP JSON request body containing one log record.
        """
        event_data = dict(event)
        resource_attributes = cls._resource_attributes(event_data)
        log_attributes = cls._log_attributes(event_data)
        timestamp_ns = cls._timestamp_to_nanoseconds(event_data.get("timestamp"))
        severity_text = str(event_data.get("severity", "INFO")).upper()
        severity_number = cls._SEVERITY_NUMBERS.get(severity_text, 9)

        log_record: dict[str, Any] = {
            "timeUnixNano": str(timestamp_ns),
            "severityNumber": severity_number,
            "severityText": severity_text,
            "body": {"stringValue": str(event_data.get("message", ""))},
        }
        if log_attributes:
            log_record["attributes"] = log_attributes

        scope_attributes = []
        if "sdk.name" in event_data:
            scope_attributes.append(
                cls._attribute("dt3.sdk.name", event_data["sdk.name"])
            )
        if "sdk.version" in event_data:
            scope_attributes.append(
                cls._attribute("dt3.sdk.version", event_data["sdk.version"])
            )

        scope_log: dict[str, Any] = {"logRecords": [log_record]}
        if scope_attributes:
            scope_log["scope"] = {"name": "dt3.logger", "attributes": scope_attributes}

        return {
            "resourceLogs": [
                {
                    "resource": {"attributes": resource_attributes},
                    "scopeLogs": [scope_log],
                }
            ]
        }

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Provide the synchronous OTLP lifecycle flush operation.

        Exports complete before ``export`` returns, so no buffered output remains.
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("OTLP transport is closed")

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Close this transport and prevent future exports.

        The operation is idempotent because OTLP/HTTP exports do not retain
        persistent connections or background resources.
        """
        with self._lock:
            self._closed = True

    @classmethod
    def _resource_attributes(cls, event: Mapping[str, Any]) -> list[dict[str, Any]]:
        """Build OTLP resource attributes from DT3 service metadata."""
        attributes: list[dict[str, Any]] = []
        for key in (
            "service.name",
            "service.version",
            "deployment.environment",
            "tenant.id",
            "tenant.name",
        ):
            if key in event:
                attributes.append(cls._attribute(key, event[key]))
        return attributes

    @classmethod
    def _log_attributes(cls, event: Mapping[str, Any]) -> list[dict[str, Any]]:
        """Build OTLP log-record attributes from non-resource DT3 fields."""
        excluded = {
            "timestamp",
            "severity",
            "message",
            "service.name",
            "service.version",
            "deployment.environment",
            "tenant.id",
            "tenant.name",
            "sdk.name",
            "sdk.version",
        }
        return [
            cls._attribute(key, value)
            for key, value in event.items()
            if key not in excluded
        ]

    @staticmethod
    def _attribute(key: str, value: Any) -> dict[str, Any]:
        """Convert a DT3 value into an OTLP JSON key-value attribute."""
        return {"key": str(key), "value": OtlpTransport._any_value(value)}

    @staticmethod
    def _any_value(value: Any) -> dict[str, Any]:
        """Encode a Python value using an OTLP JSON AnyValue shape."""
        if isinstance(value, bool):
            return {"boolValue": value}
        if isinstance(value, int):
            return {"intValue": str(value)}
        if isinstance(value, float):
            return {"doubleValue": value}
        if isinstance(value, str):
            return {"stringValue": value}
        if value is None:
            return {"stringValue": "null"}
        if isinstance(value, Mapping):
            return {
                "kvlistValue": {
                    "values": [
                        OtlpTransport._attribute(str(key), item)
                        for key, item in value.items()
                    ]
                }
            }
        if isinstance(value, (list, tuple)):
            return {
                "arrayValue": {
                    "values": [OtlpTransport._any_value(item) for item in value]
                }
            }
        return {"stringValue": str(value)}

    @staticmethod
    def _timestamp_to_nanoseconds(value: Any) -> int:
        """Convert an RFC 3339 timestamp to OTLP Unix epoch nanoseconds."""
        if not isinstance(value, str):
            return int(datetime.now(timezone.utc).timestamp() * 1_000_000_000)

        try:
            normalized = value.replace("Z", "+00:00")
            timestamp = datetime.fromisoformat(normalized)
            if timestamp.tzinfo is None:
                timestamp = timestamp.replace(tzinfo=timezone.utc)
            return int(timestamp.timestamp() * 1_000_000_000)
        except ValueError:
            return int(datetime.now(timezone.utc).timestamp() * 1_000_000_000)
