"""Tests for remaining tech-lead gaps: Kafka/Event Hub, AI request/response, auto trace ids."""

from __future__ import annotations

import json
import re
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread
from typing import Any, Dict, List

from dt3_sdk import (
    EventEmitter,
    build_ai_request_event,
    build_ai_response_event,
    create_logger,
)


class _CapturingHandler(BaseHTTPRequestHandler):
    bodies: List[bytes] = []

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        _CapturingHandler.bodies.append(self.rfile.read(length))
        self.send_response(200)
        self.end_headers()

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A003
        return


def _start_server() -> tuple[HTTPServer, str]:
    server = HTTPServer(("127.0.0.1", 0), _CapturingHandler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    return server, f"http://{host}:{port}"


def test_kafka_exporter_posts_rest_proxy_records() -> None:
    _CapturingHandler.bodies = []
    server, base = _start_server()
    try:
        logger = create_logger(
            {
                "service.name": "svc",
                "service.version": "1.0.0",
                "deployment.environment": "test",
                "validation.mode": "OFF",
                "exporter": "kafka",
                "exporter.kafka.topic": "dt3-events",
                "exporter.kafka.rest_endpoint": base,
            }
        )
        logger.info("kafka", {"event.name": "KAFKA_TEST"})
        logger.flush()
        assert len(_CapturingHandler.bodies) == 1
        payload = json.loads(_CapturingHandler.bodies[0].decode("utf-8"))
        assert "records" in payload
        assert payload["records"][0]["value"]["event.name"] == "KAFKA_TEST"
        logger.close()
    finally:
        server.shutdown()


def test_ai_request_response_share_request_id() -> None:
    request = build_ai_request_event(
        prompt="hello",
        model="gpt-4o",
        request_id="req-42",
        temperature=0.2,
    )
    response = build_ai_response_event(
        response="world",
        request_id="req-42",
        tokens_prompt=3,
        tokens_completion=1,
        tokens_total=4,
        cost=0.01,
        finish_reason="stop",
    )
    assert request["event.name"] == "AI_PROMPT_SUBMITTED"
    assert response["event.name"] == "AI_RESPONSE_RECEIVED"
    assert request["kavia.request.id"] == response["kavia.request.id"] == "req-42"

    sink_events: List[Dict[str, Any]] = []

    class Sink:
        def export(self, event):
            sink_events.append(dict(event))

        def flush(self):
            return None

        def close(self):
            return None

    logger = create_logger(
        {
            "service.name": "svc",
            "service.version": "1.0.0",
            "deployment.environment": "test",
            "validation.mode": "OFF",
            "sinks": [Sink()],
            "exporter": "stdout",
        }
    )
    emitter = EventEmitter(logger)
    emitter.emit_ai_request(prompt="p", request_id="req-9", model="m")
    emitter.emit_ai_response(response="r", request_id="req-9", tokens_total=2)
    assert len(sink_events) >= 2
    assert sink_events[0]["kavia.request.id"] == sink_events[1]["kavia.request.id"] == "req-9"
    logger.close()


def test_auto_generates_trace_and_span_ids() -> None:
    sink_events: List[Dict[str, Any]] = []

    class Sink:
        def export(self, event):
            sink_events.append(dict(event))

        def flush(self):
            return None

        def close(self):
            return None

    logger = create_logger(
        {
            "service.name": "svc",
            "service.version": "1.0.0",
            "deployment.environment": "test",
            "validation.mode": "OFF",
            "sinks": [Sink()],
            "exporter": "stdout",
        }
    )
    logger.info("traced", {"event.name": "AUTO_TRACE"})
    event = sink_events[0]
    assert re.fullmatch(r"[a-f0-9]{32}", event["trace.id"])
    assert re.fullmatch(r"[a-f0-9]{16}", event["span.id"])
    logger.close()
