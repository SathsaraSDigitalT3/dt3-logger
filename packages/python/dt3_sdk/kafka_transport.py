"""Kafka and Azure Event Hubs transports for final DT3 log events.

Uses HTTP gateways so the SDK has no mandatory Kafka client dependency:
- ``kafka`` exporter posts Confluent-style REST Proxy JSON records
- ``eventhub`` exporter posts to the Event Hubs HTTPS messages endpoint

Native Kafka brokers are reached via a REST Proxy (or compatible gateway).
Azure Event Hubs Kafka protocol endpoints may also be fronted by HTTPS send URLs.
"""

from __future__ import annotations

import json
import threading
from typing import Any, Mapping, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .errors import Dt3ErrorCode, Dt3TransportError


class KafkaTransportError(Dt3TransportError):
    """Raised when a Kafka or Event Hub export fails."""

    code = Dt3ErrorCode.TRANSPORT_UNAVAILABLE
    retryable = True


class KafkaTransport:
    """POST canonical events to a Kafka REST Proxy topic endpoint."""

    def __init__(
        self,
        topic: str,
        rest_endpoint: str,
        timeout: float = 10.0,
        headers: Optional[Mapping[str, str]] = None,
    ) -> None:
        if not isinstance(topic, str) or not topic.strip():
            raise ValueError("exporter.kafka.topic must be configured for the kafka exporter")
        if not isinstance(rest_endpoint, str) or not rest_endpoint.strip():
            raise ValueError(
                "exporter.kafka.rest_endpoint must be configured for the kafka exporter"
            )
        if timeout <= 0:
            raise ValueError("exporter.kafka.timeout must be greater than zero")

        self._topic = topic.strip()
        base = rest_endpoint.strip().rstrip("/")
        self._endpoint = f"{base}/topics/{self._topic}"
        self._timeout = float(timeout)
        self._headers = _validate_headers(headers, "exporter.kafka.headers")
        self._lock = threading.Lock()
        self._closed = False

    def export(self, event: Mapping[str, Any]) -> None:
        payload = {
            "records": [{"value": dict(event)}],
        }
        request_headers = {
            "Content-Type": "application/vnd.kafka.json.v2+json",
            "Accept": "application/vnd.kafka.v2+json, application/vnd.kafka+json, application/json",
            **self._headers,
        }
        _post_json(
            self._endpoint,
            payload,
            self._timeout,
            request_headers,
            self._lock,
            lambda: self._closed,
            "Kafka",
        )

    def flush(self) -> None:
        return None

    def close(self) -> None:
        with self._lock:
            self._closed = True


class EventHubTransport:
    """POST canonical events to an Azure Event Hubs HTTPS messages endpoint."""

    def __init__(
        self,
        endpoint: str,
        timeout: float = 10.0,
        headers: Optional[Mapping[str, str]] = None,
    ) -> None:
        if not isinstance(endpoint, str) or not endpoint.strip():
            raise ValueError(
                "exporter.eventhub.endpoint must be configured for the eventhub exporter"
            )
        if timeout <= 0:
            raise ValueError("exporter.eventhub.timeout must be greater than zero")

        self._endpoint = endpoint.strip()
        self._timeout = float(timeout)
        self._headers = _validate_headers(headers, "exporter.eventhub.headers")
        self._lock = threading.Lock()
        self._closed = False

    def export(self, event: Mapping[str, Any]) -> None:
        request_headers = {
            "Content-Type": "application/json",
            **self._headers,
        }
        _post_json(
            self._endpoint,
            dict(event),
            self._timeout,
            request_headers,
            self._lock,
            lambda: self._closed,
            "Event Hub",
        )

    def flush(self) -> None:
        return None

    def close(self) -> None:
        with self._lock:
            self._closed = True


def _validate_headers(
    headers: Optional[Mapping[str, str]],
    key: str,
) -> dict[str, str]:
    if headers is None:
        return {}
    if not isinstance(headers, Mapping):
        raise ValueError(f"{key} must be a mapping of string header names to string values")
    validated: dict[str, str] = {}
    for name, value in headers.items():
        if not isinstance(name, str) or not name.strip() or "\r" in name or "\n" in name:
            raise ValueError(f"{key} contains an invalid header name")
        if not isinstance(value, str) or "\r" in value or "\n" in value:
            raise ValueError(f"{key}[{name!r}] contains an invalid header value")
        validated[name] = value
    return validated


def _post_json(
    endpoint: str,
    payload: Any,
    timeout: float,
    headers: Mapping[str, str],
    lock: threading.Lock,
    is_closed,
    label: str,
) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(endpoint, data=body, headers=dict(headers), method="POST")
    with lock:
        if is_closed():
            raise RuntimeError(f"{label} transport is closed")
        try:
            with urlopen(request, timeout=timeout) as response:
                status = getattr(response, "status", None) or response.getcode()
                if not (200 <= int(status) < 300):
                    raise KafkaTransportError(
                        f"{label} export request failed with status {status}"
                    )
        except KafkaTransportError:
            raise
        except HTTPError as error:
            raise KafkaTransportError(
                f"{label} export request failed with status {error.code}"
            ) from error
        except URLError as error:
            raise KafkaTransportError(f"{label} export request failed") from error
        except TimeoutError as error:
            err = KafkaTransportError(f"{label} export request timed out")
            err.code = Dt3ErrorCode.TRANSPORT_TIMEOUT
            raise err from error
