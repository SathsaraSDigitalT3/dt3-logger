package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.MaskingEngine;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recursively masks configured sensitive fields without modifying source data.
 *
 * @since 0.1.0
 */
public final class RecursiveMaskingEngine implements MaskingEngine {

    /** Default field names defined by specs/masking.yaml. */
    public static final List<String> DEFAULT_SENSITIVE_FIELDS = List.of(
        "password",
        "passwd",
        "pwd",
        "secret",
        "token",
        "access_token",
        "refresh_token",
        "authorization",
        "api_key",
        "apikey",
        "private_key",
        "credit_card",
        "card_number",
        "ssn",
        "nic",
        "national_id",
        "email",
        "phone",
        "prompt",
        "response",
        "kavia.prompt",
        "kavia.response"
    );

    private static final String DEFAULT_REPLACEMENT = "[REDACTED]";

    private final Set<String> sensitiveFields;
    private final boolean enabled;
    private final boolean trackMaskedFields;
    private final String replacementValue;
    private final List<String> maskedFields = new ArrayList<>();

    /**
     * Create an engine with default sensitive fields and enabled masking.
     */
    public RecursiveMaskingEngine() {
        this(List.of(), DEFAULT_REPLACEMENT, false, true);
    }

    /**
     * Create an engine with supported masking options.
     *
     * @param additionalSensitiveFields field names to add to the default list
     * @param replacementValue value replacing masked values
     * @param trackMaskedFields whether to retain masked field paths
     * @param enabled whether masking is enabled
     */
    public RecursiveMaskingEngine(
        List<String> additionalSensitiveFields,
        String replacementValue,
        boolean trackMaskedFields,
        boolean enabled
    ) {
        this.sensitiveFields = new LinkedHashSet<>();
        DEFAULT_SENSITIVE_FIELDS.forEach(this::addSensitiveField);

        if (additionalSensitiveFields != null) {
            additionalSensitiveFields.forEach(this::addSensitiveField);
        }

        this.replacementValue = replacementValue == null ? DEFAULT_REPLACEMENT : replacementValue;
        this.trackMaskedFields = trackMaskedFields;
        this.enabled = enabled;
    }

    // PUBLIC_INTERFACE
    /**
     * Return a recursively copied map with sensitive field values redacted.
     *
     * @param data event or context data to mask
     * @return an independent masked copy of the supplied map
     */
    @Override
    public Map<String, Object> mask(Map<String, Object> data) {
        maskedFields.clear();

        if (data == null) {
            return new LinkedHashMap<>();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> copiedData = (Map<String, Object>) maskValue(data, "");
        return copiedData;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the supported sensitive field names, including configured additions.
     *
     * @return an immutable ordered list of lower-case sensitive field names
     */
    @Override
    public List<String> getSensitiveFields() {
        return List.copyOf(sensitiveFields);
    }

    /**
     * Return paths masked in the most recent call when tracking is enabled.
     *
     * @return a copy of tracked field paths, or an empty list when disabled
     */
    public List<String> getMaskedFields() {
        return trackMaskedFields ? List.copyOf(maskedFields) : List.of();
    }

    private void addSensitiveField(String field) {
        if (field != null) {
            sensitiveFields.add(field.toLowerCase(Locale.ROOT));
        }
    }

    private Object maskValue(Object value, String path) {
        if (!enabled) {
            return copyValue(value);
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> maskedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;

                if (sensitiveFields.contains(key.toLowerCase(Locale.ROOT))) {
                    maskedMap.put(key, replacementValue);
                    if (trackMaskedFields) {
                        maskedFields.add(childPath);
                    }
                } else {
                    maskedMap.put(key, maskValue(entry.getValue(), childPath));
                }
            }
            return maskedMap;
        }

        if (value instanceof Iterable<?> iterable) {
            List<Object> maskedList = new ArrayList<>();
            int index = 0;
            for (Object element : iterable) {
                maskedList.add(maskValue(element, path + "[" + index + "]"));
                index++;
            }
            return maskedList;
        }

        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> maskedList = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                maskedList.add(maskValue(Array.get(value, index), path + "[" + index + "]"));
            }
            return maskedList;
        }

        return value;
    }

    private Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copiedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copiedMap.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            }
            return copiedMap;
        }

        if (value instanceof Iterable<?> iterable) {
            List<Object> copiedList = new ArrayList<>();
            for (Object element : iterable) {
                copiedList.add(copyValue(element));
            }
            return copiedList;
        }

        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copiedList = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copiedList.add(copyValue(Array.get(value, index)));
            }
            return copiedList;
        }

        return value;
    }
}
