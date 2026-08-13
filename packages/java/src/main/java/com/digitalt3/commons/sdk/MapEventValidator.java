package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.ValidationMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Internal validator for the map-based events emitted by {@link StdoutLogger}.
 *
 * <p>The validator evaluates the packaged canonical {@code log-event.schema.json}
 * document, preserving validation of the exact flat event map sent to stdout
 * without changing the public {@code Logger} API.</p>
 *
 * @since 0.1.0
 */
final class MapEventValidator {

    private static final String CANONICAL_SCHEMA_RESOURCE = "/schemas/log-event.schema.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CanonicalSchema CANONICAL_SCHEMA = loadCanonicalSchema();

    /**
     * Validate an event against the complete canonical log-event JSON Schema.
     *
     * @param event flat dot-keyed event map
     * @return sanitized validation diagnostics, or an empty list for a valid event
     */
    List<String> validate(Map<String, Object> event) {
        JsonNode eventNode = OBJECT_MAPPER.valueToTree(event);
        Set<ValidationMessage> validationMessages = CANONICAL_SCHEMA.schema().validate(eventNode);

        List<String> errors = new ArrayList<>(validationMessages.size());
        for (ValidationMessage validationMessage : validationMessages) {
            errors.add(sanitize(validationMessage, event));
        }
        return List.copyOf(errors);
    }

    /**
     * Apply the configured validation behavior to an event already prepared for export.
     *
     * @param event event that has already been masked
     * @param mode configured validation mode
     * @return validation diagnostics for LENIENT export
     * @throws IllegalArgumentException if no mode is configured or STRICT validation fails
     */
    List<String> apply(Map<String, Object> event, ValidationMode mode) {
        ValidationMode resolvedMode = Objects.requireNonNull(
            mode,
            "validationMode must be STRICT, LENIENT, or OFF"
        );

        if (resolvedMode == ValidationMode.OFF) {
            return List.of();
        }

        List<String> errors = validate(event);
        if (errors.isEmpty() || resolvedMode == ValidationMode.LENIENT) {
            return errors;
        }

        throw new IllegalArgumentException(
            "Log event validation failed: " + String.join("; ", errors)
        );
    }

    private static CanonicalSchema loadCanonicalSchema() {
        try (InputStream schemaStream = MapEventValidator.class.getResourceAsStream(
            CANONICAL_SCHEMA_RESOURCE
        )) {
            if (schemaStream == null) {
                throw new IllegalStateException(
                    "Canonical log-event schema resource is missing: " + CANONICAL_SCHEMA_RESOURCE
                );
            }

            JsonNode schemaNode = OBJECT_MAPPER.readTree(schemaStream);
            return new CanonicalSchema(
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode),
                requiredProperties(schemaNode)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load canonical log-event schema", exception);
        }
    }

    private static List<String> requiredProperties(JsonNode schemaNode) {
        JsonNode requiredNode = schemaNode.path("required");
        if (!requiredNode.isArray()) {
            return List.of();
        }

        List<String> requiredProperties = new ArrayList<>(requiredNode.size());
        for (JsonNode requiredProperty : requiredNode) {
            if (requiredProperty.isTextual()) {
                requiredProperties.add(requiredProperty.textValue());
            }
        }
        return List.copyOf(requiredProperties);
    }

    private String sanitize(ValidationMessage validationMessage, Map<String, Object> event) {
        String instanceLocation = diagnosticLocation(validationMessage);
        String validationCategory = validationMessage.getType();

        if ("required".equals(validationCategory)) {
            return "Required property is missing: " + requiredProperty(event)
                + " at " + instanceLocation;
        }
        if ("type".equals(validationCategory)) {
            return "Value has an invalid type at " + instanceLocation;
        }
        if ("format".equals(validationCategory)) {
            return "Value has an invalid format at " + instanceLocation;
        }
        if ("pattern".equals(validationCategory)) {
            return "Value has an invalid pattern at " + instanceLocation;
        }
        if ("enum".equals(validationCategory)) {
            return "Value is not an allowed value at " + instanceLocation;
        }
        if ("minLength".equals(validationCategory)) {
            return "Value is shorter than the minimum length at " + instanceLocation;
        }
        if ("minimum".equals(validationCategory)) {
            return "Value is below the minimum at " + instanceLocation;
        }

        return "Schema validation failed at " + instanceLocation;
    }

    /**
     * Resolve the first schema-required property absent from the final event map.
     *
     * <p>The canonical schema determines both the required fields and their
     * diagnostic order. Looking at the final map avoids relying on validator
     * message formatting and reflects caller context overrides applied by
     * {@link StdoutLogger} before validation.</p>
     *
     * @param event final event map being validated
     * @return first absent canonical required property, or a generic label if unavailable
     */
    private String requiredProperty(Map<String, Object> event) {
        for (String requiredProperty : CANONICAL_SCHEMA.requiredProperties()) {
            if (!event.containsKey(requiredProperty)) {
                return requiredProperty;
            }
        }
        return "property";
    }

    /**
     * Resolve a stable JSON Pointer for a validation failure.
     *
     * <p>NetworkNT 1.5.9 reports flat dotted event keys through the schema
     * location for property-level constraints. Its instance location can
     * instead point at the root object, so prefer the field identified after
     * the schema {@code properties} segment. Required-property failures have
     * no property subschema and correctly retain the validator's root
     * location.</p>
     *
     * @param validationMessage schema validation failure
     * @return a structural JSON Pointer that never includes the rejected value
     */
    private String diagnosticLocation(ValidationMessage validationMessage) {
        String schemaLocation = validationMessage.getSchemaLocation().toString();
        String propertyMarker = "/properties/";
        int propertyIndex = schemaLocation.lastIndexOf(propertyMarker);

        if (propertyIndex >= 0) {
            String propertyPath = schemaLocation.substring(propertyIndex + propertyMarker.length());
            int nestedSchemaIndex = propertyPath.indexOf('/');
            String schemaProperty = nestedSchemaIndex >= 0
                ? propertyPath.substring(0, nestedSchemaIndex)
                : propertyPath;

            if (!schemaProperty.isEmpty()) {
                return "/" + decodeSchemaProperty(schemaProperty)
                    .replace("~", "~0")
                    .replace("/", "~1");
            }
        }

        return validationMessage.getInstanceLocation().toString();
    }

    /**
     * Decode the JSON Pointer escaping used in a schema-location property segment.
     *
     * <p>Schema locations encode literal slash and tilde characters in property
     * names. The returned value is re-encoded as an instance JSON Pointer by
     * {@link #diagnosticLocation(ValidationMessage)} so the diagnostic references
     * the event's literal flat map key.</p>
     *
     * @param schemaProperty property segment extracted from a schema location
     * @return the literal event-map property name
     */
    private String decodeSchemaProperty(String schemaProperty) {
        return schemaProperty.replace("~1", "/").replace("~0", "~");
    }

    /**
     * Parsed packaged schema and its ordered root required-property list.
     *
     * @param schema compiled schema used for validation
     * @param requiredProperties ordered required root property names
     */
    private record CanonicalSchema(JsonSchema schema, List<String> requiredProperties) {
    }
}
