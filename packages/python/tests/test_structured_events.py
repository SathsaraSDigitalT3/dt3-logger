"""Tests for the structured event framework gap-close (schema 1.1.0)."""

from __future__ import annotations

import re
from typing import Any, Dict, List, Mapping
from unittest.mock import patch

from dt3_sdk import (
    EventEmitter,
    MaskingEngine,
    MultiSinkFanout,
    StdoutSink,
    build_ai_event,
    build_api_event,
    build_db_event,
    build_messaging_event,
    create_logger,
    create_tracer,
    wrap_log_event,
)


UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)


class CapturingSink:
    """Test sink that records exported events."""

    def __init__(self) -> None:
        self.events: List[Dict[str, Any]] = []
        self.flush_calls = 0
        self.close_calls = 0

    def export(self, event: Mapping[str, Any]) -> None:
        self.events.append(dict(event))

    def flush(self) -> None:
        self.flush_calls += 1

    def close(self) -> None:
        self.close_calls += 1


class FailingSink:
    """Test sink that always fails export."""

    def export(self, event: Mapping[str, Any]) -> None:
        raise RuntimeError("sink failed")

    def flush(self) -> None:
        pass

    def close(self) -> None:
        pass


def _base_config(**extra: Any) -> Dict[str, Any]:
    config: Dict[str, Any] = {
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "validation.mode": "OFF",
    }
    config.update(extra)
    return config


def test_auto_generates_event_id_and_defaults_schema_version() -> None:
    sink = CapturingSink()
    logger = create_logger(
        _base_config(
            sinks=[sink],
            **{"component.name": "orders-api"},
        )
    )

    with patch.object(StdoutSink, "export"):
        logger.info("hello", {"event.name": "TEST_EVENT"})

    assert len(sink.events) == 1
    event = sink.events[0]
    assert UUID_RE.match(event["event.id"])
    assert event["schema.version"] == "1.1.0"
    assert event["component.name"] == "orders-api"


def test_preserves_explicit_event_id_and_operation_id() -> None:
    sink = CapturingSink()
    logger = create_logger(_base_config(sinks=[sink]))

    with patch.object(StdoutSink, "export"):
        logger.info(
            "op",
            {
                "event.name": "OP_EVENT",
                "event.id": "evt-fixed",
                "operation.id": "op-123",
            },
        )

    event = sink.events[0]
    assert event["event.id"] == "evt-fixed"
    assert event["operation.id"] == "op-123"


def test_multi_sink_fanout_isolates_failures() -> None:
    good = CapturingSink()
    bad = FailingSink()
    also_good = CapturingSink()
    errors: List[str] = []

    fanout = MultiSinkFanout(
        [("bad", bad), ("good", good), ("also", also_good)],
        on_error=lambda name, err: errors.append(name),
    )
    event = {"message": "x", "event.name": "FANOUT"}
    fanout.export(event)

    assert len(good.events) == 1
    assert len(also_good.events) == 1
    assert errors == ["bad"]


def test_register_sink_receives_events() -> None:
    primary = CapturingSink()
    logger = create_logger(_base_config(sinks=[primary]))
    secondary = CapturingSink()
    logger.register_sink(secondary, name="secondary")

    with patch.object(StdoutSink, "export"):
        logger.info("fan", {"event.name": "REGISTERED"})

    assert len(primary.events) == 1
    assert len(secondary.events) == 1
    assert primary.events[0]["event.name"] == "REGISTERED"
    assert secondary.events[0]["event.id"] == primary.events[0]["event.id"]


def test_typed_builders_and_emitter() -> None:
    sink = CapturingSink()
    logger = create_logger(_base_config(sinks=[sink]))
    emitter = EventEmitter(logger)

    api = build_api_event(
        "INCOMING_HTTP",
        "GET /orders",
        method="GET",
        route="/orders",
        status_code=200,
        duration_ms=12.5,
    )
    assert api["http.request.method"] == "GET"
    assert api["http.response.status_code"] == 200

    db = build_db_event(
        "DB_QUERY_COMPLETED",
        "select",
        system="postgresql",
        operation="SELECT",
        table="orders",
    )
    assert db["db.system"] == "postgresql"
    assert db["db.sql.table"] == "orders"

    messaging = build_messaging_event(
        "MESSAGING_PUBLISH",
        "published",
        system="kafka",
        destination="orders",
        message_id="m-1",
    )
    assert messaging["messaging.destination"] == "orders"

    ai = build_ai_event(
        "AI_PROMPT_SUBMITTED",
        "prompt",
        provider="openai",
        model="gpt-4",
        prompt="secret prompt",
        tokens_prompt=10,
    )
    assert ai["kavia.provider"] == "openai"
    assert ai["kavia.tokens.prompt"] == 10

    with patch.object(StdoutSink, "export"):
        emitter.emit_api("INCOMING_HTTP", "ok", method="POST", status_code=201)
        emitter.emit_db("DB_QUERY_STARTED", "start", system="mysql")
        emitter.emit_messaging("WORKER_JOB_STARTED", "start", message_id="j1")
        emitter.emit_ai("AI_RESPONSE_RECEIVED", "done", tokens_completion=5)

    assert len(sink.events) == 4
    assert sink.events[0]["http.request.method"] == "POST"
    assert sink.events[1]["db.system"] == "mysql"
    assert sink.events[2]["messaging.message.id"] == "j1"
    assert sink.events[3]["kavia.tokens.completion"] == 5

    envelope = wrap_log_event(sink.events[0])
    assert envelope["event_type"] == "INCOMING_HTTP"
    assert envelope["payload"]["event.name"] == "INCOMING_HTTP"


def test_ai_masking_redacts_prompt_not_token_counts() -> None:
    engine = MaskingEngine(track_masked_fields=True)
    masked, fields = engine.mask(
        {
            "kavia.prompt": "user secret",
            "kavia.response": "model secret",
            "prompt": "also secret",
            "response": "also secret response",
            "kavia.tokens.prompt": 42,
            "kavia.tokens.completion": 7,
            "kavia.tokens.total": 49,
        }
    )

    assert masked["kavia.prompt"] == "[REDACTED]"
    assert masked["kavia.response"] == "[REDACTED]"
    assert masked["prompt"] == "[REDACTED]"
    assert masked["response"] == "[REDACTED]"
    assert masked["kavia.tokens.prompt"] == 42
    assert masked["kavia.tokens.completion"] == 7
    assert masked["kavia.tokens.total"] == 49
    assert "kavia.prompt" in fields
    assert "kavia.tokens.prompt" not in fields

    sink = CapturingSink()
    logger = create_logger(
        _base_config(
            sinks=[sink],
            **{"masking.track_masked_fields": True},
        )
    )
    with patch.object(StdoutSink, "export"):
        logger.event(
            {
                "message": "ai",
                "event.name": "AI_PROMPT_SUBMITTED",
                "kavia.prompt": "hide me",
                "kavia.tokens.prompt": 3,
            }
        )

    event = sink.events[0]
    assert event["kavia.prompt"] == "[REDACTED]"
    assert event["kavia.tokens.prompt"] == 3


def test_span_nesting_sets_trace_and_parent_ids() -> None:
    sink = CapturingSink()
    logger = create_logger(
        _base_config(
            sinks=[sink],
            **{"tracing.span_events.enabled": True},
        )
    )
    tracer = create_tracer(logger)

    with patch.object(StdoutSink, "export"):
        outer = tracer.start_span("outer-work")
        outer_ctx = dict(outer.context)
        assert re.fullmatch(r"[a-f0-9]{32}", outer_ctx["trace.id"])
        assert re.fullmatch(r"[a-f0-9]{16}", outer_ctx["span.id"])
        assert "parent.span.id" not in outer_ctx

        logger.info("inside outer", {"event.name": "INSIDE_OUTER"})

        inner = tracer.start_span("inner-work")
        inner_ctx = dict(inner.context)
        assert inner_ctx["trace.id"] == outer_ctx["trace.id"]
        assert inner_ctx["parent.span.id"] == outer_ctx["span.id"]
        assert inner_ctx["span.id"] != outer_ctx["span.id"]

        logger.info("inside inner", {"event.name": "INSIDE_INNER"})
        inner.end()
        outer.end()

    by_name = {event["event.name"]: event for event in sink.events}
    assert by_name["INSIDE_OUTER"]["trace.id"] == outer_ctx["trace.id"]
    assert by_name["INSIDE_OUTER"]["span.id"] == outer_ctx["span.id"]
    assert by_name["INSIDE_INNER"]["span.id"] == inner_ctx["span.id"]
    assert by_name["INSIDE_INNER"]["parent.span.id"] == outer_ctx["span.id"]
    assert by_name["OUTER_WORK"]["duration.ms"] >= 0
    assert by_name["INNER_WORK"]["duration.ms"] >= 0
