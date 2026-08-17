"""Synchronous HTTP transport for final DT3 structured log events."""

from __future__ import annotations

import json
import threading
from typing import Any, Mapping, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class HttpTransportError(RuntimeError):
    """Raised when the HTTP transport cannot successfully export an event."""


class HttpTransport:
    """Synchronously send final DT3 log events as JSON to an HTTP endpoint."""

    def __init__(
        self,
        endpoint: str,
        timeout: float = 10.0,
        headers: Optional[Mapping[str, str]] = None,
    ) -> None:
        """Create an HTTP transport.

        Args:
            endpoint: Destination URL that receives JSON log events.
            timeout: Maximum number of seconds allowed for each HTTP request.
            headers: Optional request headers merged with the JSON content type.

        Raises:
            ValueError: If the endpoint is blank, the timeout is not positive, or
                configured headers are invalid.
        """
        if not isinstance(endpoint, str) or not endpoint.strip():
            raise ValueError("http.endpoint must be configured for the HTTP exporter")
        if timeout <= 0:
            raise ValueError("http.timeout must be greater than zero")
        if headers is not None and not isinstance(headers, Mapping):
            raise ValueError("http.headers must be a mapping of string header names to string values")

        validated_headers: dict[str, str] = {}
        for name, value in (headers or {}).items():
            if not isinstance(name, str) or not name.strip():
                raise ValueError(
                    f"http.headers contains an invalid header name: {name!r}"
                )
            if "\r" in name or "\n" in name:
                raise ValueError("http.headers contains an invalid header name")
            if not isinstance(value, str):
                raise ValueError(
                    f"http.headers[{name!r}] must have a string header value; got {value!r}"
                )
            if "\r" in value or "\n" in value:
                raise ValueError(
                    f"http.headers[{name!r}] contains an invalid header value"
                )
            validated_headers[name] = value

        self._endpoint = endpoint
        self._timeout = timeout
        self._headers = validated_headers
        self._lock = threading.Lock()
        self._closed = False

    # PUBLIC_INTERFACE
    def export(self, event: Mapping[str, Any]) -> None:
        """Synchronously POST one final DT3 event as an application/json payload.

        Args:
            event: The already-masked and validation-processed canonical log event.

        Raises:
            RuntimeError: If the transport has already been closed.
            HttpTransportError: If the request fails, times out, or returns an HTTP
                error response.
            TypeError: If the final event cannot be JSON serialized.
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("HTTP transport is closed")

            payload = json.dumps(
                dict(event),
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
                        raise HttpTransportError(
                            f"HTTP export failed with status {status}"
                        )
            except HTTPError as error:
                raise HttpTransportError(
                    f"HTTP export failed with status {error.code}"
                ) from error
            except URLError as error:
                reason = "request failed"
                if getattr(error, "reason", None) is not None:
                    reason = str(error.reason)
                raise HttpTransportError(
                    f"HTTP export request failed: {reason}"
                ) from error
            except TimeoutError as error:
                raise HttpTransportError("HTTP export request timed out") from error

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Provide the synchronous transport lifecycle flush operation.

        HTTP exports complete before ``export`` returns, so no buffered output
        remains to flush.
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("HTTP transport is closed")

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Close this transport and prevent further exports.

        This operation is idempotent because the synchronous transport owns no
        persistent connection or background resources.
        """
        with self._lock:
            self._closed = True
