package com.digitalt3.commons.api.events;

import com.digitalt3.commons.api.LogEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed builders for AI observability events using the {@code kavia.*} namespace.
 *
 * @since 0.1.0
 */
public final class AiEvents {

    private AiEvents() {
    }

    public static LogEvent promptSubmitted(Map<String, Object> kaviaAttributes) {
        return build("AI_PROMPT_SUBMITTED", kaviaAttributes);
    }

    public static LogEvent responseReceived(Map<String, Object> kaviaAttributes) {
        return build("AI_RESPONSE_RECEIVED", kaviaAttributes);
    }

    public static LogEvent toolInvocation(Map<String, Object> kaviaAttributes) {
        return build("AI_TOOL_INVOCATION", kaviaAttributes);
    }

    public static LogEvent memoryRetrieval(Map<String, Object> kaviaAttributes) {
        return build("AI_MEMORY_RETRIEVAL", kaviaAttributes);
    }

    public static LogEvent ragRetrieval(Map<String, Object> kaviaAttributes) {
        return build("AI_RAG_RETRIEVAL", kaviaAttributes);
    }

    public static LogEvent agentExecution(Map<String, Object> kaviaAttributes) {
        return build("AI_AGENT_EXECUTION", kaviaAttributes);
    }

    public static LogEvent safetyFilterApplied(Map<String, Object> kaviaAttributes) {
        return build("AI_SAFETY_FILTER_APPLIED", kaviaAttributes);
    }

    public static Map<String, Object> asMap(String eventName, Map<String, Object> fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event.name", eventName);
        if (fields != null) {
            map.putAll(fields);
        }
        return map;
    }

    private static LogEvent build(String eventName, Map<String, Object> kaviaAttributes) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (kaviaAttributes != null) {
            attrs.putAll(kaviaAttributes);
        }
        return LogEvent.builder()
            .severity("INFO")
            .message(eventName)
            .eventName(eventName)
            .attributes(attrs)
            .build();
    }
}
