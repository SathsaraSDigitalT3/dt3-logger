"""Typed domain and AI event builders."""

from .ai import (
    build_agent_execution,
    build_ai_event,
    build_ai_request_event,
    build_ai_response_event,
    build_memory_retrieval,
    build_prompt_submitted,
    build_rag_retrieval,
    build_response_received,
    build_safety_filter_applied,
    build_tool_invocation,
)
from .api import build_api_event
from .database import build_database_event
from .envelope import wrap_log_event
from .messaging import build_messaging_event

# Public aliases matching package __init__ naming
build_db_event = build_database_event
build_ai_prompt_submitted = build_prompt_submitted
build_ai_response_received = build_response_received
build_ai_tool_invocation = build_tool_invocation
build_ai_memory_retrieval = build_memory_retrieval
build_ai_rag_retrieval = build_rag_retrieval
build_ai_agent_execution = build_agent_execution
build_ai_safety_filter_applied = build_safety_filter_applied

__all__ = [
    "build_agent_execution",
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
    "build_database_event",
    "build_db_event",
    "build_memory_retrieval",
    "build_messaging_event",
    "build_prompt_submitted",
    "build_rag_retrieval",
    "build_response_received",
    "build_safety_filter_applied",
    "build_tool_invocation",
    "wrap_log_event",
]
