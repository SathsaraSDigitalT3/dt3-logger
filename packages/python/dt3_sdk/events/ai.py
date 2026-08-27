"""AI (kavia.*) event builders."""

from __future__ import annotations

from typing import Any, Dict, Optional


def build_ai_event(
    event_name: str,
    message: str,
    *,
    provider: Optional[str] = None,
    model: Optional[str] = None,
    model_version: Optional[str] = None,
    prompt: Optional[str] = None,
    response: Optional[str] = None,
    tokens_prompt: Optional[int] = None,
    tokens_completion: Optional[int] = None,
    tokens_total: Optional[int] = None,
    latency_ms: Optional[float] = None,
    cost: Optional[float] = None,
    context_window_size: Optional[int] = None,
    memory_bytes: Optional[int] = None,
    conversation_id: Optional[str] = None,
    agent_id: Optional[str] = None,
    request_id: Optional[str] = None,
    finish_reason: Optional[str] = None,
    cache_hit: Optional[bool] = None,
    temperature: Optional[float] = None,
    max_tokens: Optional[int] = None,
    severity: str = "INFO",
    **extra: Any,
) -> Dict[str, Any]:
    """Build a canonical AI LogEvent fragment using kavia.* attributes."""
    event: Dict[str, Any] = {
        "event.name": event_name,
        "message": message,
        "severity": severity,
    }
    mapping = {
        "kavia.provider": provider,
        "kavia.model": model,
        "kavia.model.version": model_version,
        "kavia.prompt": prompt,
        "kavia.response": response,
        "kavia.tokens.prompt": tokens_prompt,
        "kavia.tokens.completion": tokens_completion,
        "kavia.tokens.total": tokens_total,
        "kavia.latency.ms": latency_ms,
        "kavia.cost": cost,
        "kavia.context_window.size": context_window_size,
        "kavia.memory.bytes": memory_bytes,
        "kavia.conversation.id": conversation_id,
        "kavia.agent.id": agent_id,
        "kavia.request.id": request_id,
        "kavia.finish_reason": finish_reason,
        "kavia.cache.hit": cache_hit,
        "kavia.temperature": temperature,
        "kavia.max_tokens": max_tokens,
    }
    for key, value in mapping.items():
        if value is not None:
            event[key] = value
    event.update(extra)
    return event


def build_prompt_submitted(message: str = "AI prompt submitted", **kwargs: Any) -> Dict[str, Any]:
    return build_ai_event("AI_PROMPT_SUBMITTED", message, **kwargs)


def build_response_received(message: str = "AI response received", **kwargs: Any) -> Dict[str, Any]:
    return build_ai_event("AI_RESPONSE_RECEIVED", message, **kwargs)


def build_tool_invocation(message: str = "AI tool invocation", **kwargs: Any) -> Dict[str, Any]:
    return build_ai_event("AI_TOOL_INVOCATION", message, **kwargs)


def build_memory_retrieval(message: str = "AI memory retrieval", **kwargs: Any) -> Dict[str, Any]:
    return build_ai_event("AI_MEMORY_RETRIEVAL", message, **kwargs)


def build_rag_retrieval(message: str = "AI RAG retrieval", **kwargs: Any) -> Dict[str, Any]:
    return build_ai_event("AI_RAG_RETRIEVAL", message, **kwargs)


def build_agent_execution(message: str = "AI agent execution", **kwargs: Any) -> Dict[str, Any]:
    return build_ai_event("AI_AGENT_EXECUTION", message, **kwargs)


def build_safety_filter_applied(
    message: str = "AI safety filter applied", **kwargs: Any
) -> Dict[str, Any]:
    return build_ai_event("AI_SAFETY_FILTER_APPLIED", message, **kwargs)
