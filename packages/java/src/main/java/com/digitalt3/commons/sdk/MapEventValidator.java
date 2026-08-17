package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.api.Validator.ValidationErrorDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Internal validator for map-based events emitted by {@link StdoutLogger}.
 *
 * <p>The validator evaluates the packaged canonical {@code log-event.schema.json}
 * document and translates schema diagnostics into sanitized structured details.
 * The same map validation path is reused by the public {@link LogEventValidator}
 * adapter without changing the logger's flat-map architecture.</p>
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
     * @return sanitized structured diagnostics, or an empty list for a valid event
     */
    List<ValidationErrorDetail> validate(Map<String, Object> event) {
        JsonNode eventNode = OBJECT_MAPPER.valueToTree(event);
        Set<ValidationMessage> validationMessages = CANONICAL_SCHEMA.schema().validate(eventNode);
        List<String> missingRequiredProperties = missingRequiredProperties(event);
        int requiredDiagnosticIndex = 0;

        List<ValidationErrorDetail> errors = new ArrayList<>(validationMessages.size() + 1);
        for (ValidationMessage validationMessage : validationMessages) {
            if ("required".equals(validationMessage.getType())) {
                String field = requiredDiagnosticIndex < missingRequiredProperties.size()
                    ? missingRequiredProperties.get(requiredDiagnosticIndex)
                    : "$";
                requiredDiagnosticIndex++;
                errors.add(new ValidationErrorDetail(
                    field,
                    "Required property is missing",
                    "required"
                ));
            } else {
                errors.add(toDetail(validationMessage, event));
            }
        }
        addTimestampFormatDiagnostic(event, errors);
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
    List<ValidationErrorDetail> apply(Map<String, Object> event, ValidationMode mode) {
        ValidationMode resolvedMode = Objects.requireNonNull(
            mode,
            "validationMode must be STRICT, LENIENT, or OFF"
        );

        if (resolvedMode == ValidationMode.OFF) {
            return List.of();
        }

        List<ValidationErrorDetail> errors = validate(event);
        if (errors.isEmpty() || resolvedMode == ValidationMode.LENIENT) {
            return errors;
        }

        throw new LogEventValidationException(
            "Log event validation failed: " + formatErrors(errors)
        );
    }

    /**
     * Render structured diagnostics for a sanitized exception message.
     *
     * @param errors structured validation details
     * @return readable diagnostics without rejected caller values
     */
    static String formatErrors(List<ValidationErrorDetail> errors) {
        List<String> messages = new ArrayList<>(errors.size());
        for (ValidationErrorDetail error : errors) {
            messages.add(error.message() + " at " + error.field());
        }
        return String.join("; ", messages);
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

    /**
     * Return the absent root-level required properties in canonical schema order.
     *
     * <p>NetworkNT emits one validation message for each missing property, but
     * its message does not reliably expose the specific property name. Pairing
     * those messages with this ordered list preserves a diagnostic for every
     * missing required field without exposing event values.</p>
     *
     * @param event final event map being validated
     * @return absent canonical required property names in schema order
     */
    private List<String> missingRequiredProperties(Map<String, Object> event) {
        List<String> missingProperties = new ArrayList<>();
        for (String requiredProperty : CANONICAL_SCHEMA.requiredProperties()) {
            if (!event.containsKey(requiredProperty)) {
                missingProperties.add(requiredProperty);
            }
        }
        return List.copyOf(missingProperties);
    }

    private ValidationErrorDetail toDetail(
        ValidationMessage validationMessage,
        Map<String, Object> event
    ) {
        String rule = validationMessage.getType();
        String field = diagnosticField(validationMessage, event);

        if ("required".equals(rule)) {
            field = requiredProperty(event);
            return new ValidationErrorDetail(
                field,
                "Required property is missing",
                rule
            );
        }

        return new ValidationErrorDetail(field, messageForRule(rule), rule);
    }

    private String messageForRule(String rule) {
        return switch (rule) {
            case "type" -> "Value has an invalid type";
            case "format" -> "Value has an invalid format";
            case "pattern" -> "Value has an invalid pattern";
            case "enum" -> "Value is not an allowed value";
            case "minLength" -> "Value is shorter than the minimum length";
            case "minimum" -> "Value is below the minimum";
            default -> "Schema validation failed";
        };
    }

    /**
     * Add the canonical timestamp format diagnostic when the schema engine does
     * not enforce JSON Schema {@code date-time} formats by default.
     *
     * <p>The shared fixture contract requires an RFC 3339 offset date-time. The
     * diagnostic deliberately contains no rejected value so it remains safe for
     * LENIENT event output and STRICT exception messages.</p>
     *
     * @param event final flat event map being validated
     * @param errors schema diagnostics to extend when needed
     */
    private void addTimestampFormatDiagnostic(
        Map<String, Object> event,
        List<ValidationErrorDetail> errors
    ) {
        Object timestamp = event.get("timestamp");
        if (!(timestamp instanceof String timestampText) || isOffsetDateTime(timestampText)) {
            return;
        }

        boolean schemaAlreadyReportedFormat = errors.stream().anyMatch(error ->
            "timestamp".equals(error.field()) && "format".equals(error.rule())
        );
        if (!schemaAlreadyReportedFormat) {
            errors.add(new ValidationErrorDetail(
                "timestamp",
                "Value has an invalid format",
                "format"
            ));
        }
    }

    /**
     * Return whether a timestamp conforms to the RFC 3339 offset date-time
     * representation required by the canonical schema.
     *
     * @param timestampText candidate timestamp text
     * @return {@code true} when the candidate is a valid offset date-time
     */
    private boolean isOffsetDateTime(String timestampText) {
        // OffsetDateTime accepts ISO-8601 variants (for example, basic offsets)
        // that are outside the shared RFC 3339 date-time contract.
        if (!timestampText.matches(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$"
        )) {
            return false;
        }
        try {
            OffsetDateTime.parse(timestampText);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
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
        return "$";
    }

    /**
     * Resolve a stable dot-keyed field for a validation failure.
     *
     * <p>NetworkNT reports flat dotted event keys through the schema location for
     * property-level constraints. Its instance location can instead point at the
     * root object, so the schema property segment is preferred whenever present.</p>
     *
     * @param validationMessage schema validation failure
     * @param event final flat event map used to validate instance-location fallbacks
     * @return the affected flat event field, or {@code $} for a root failure
     */
    private String diagnosticField(
        ValidationMessage validationMessage,
        Map<String, Object> event
    ) {
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
                return decodeSchemaProperty(schemaProperty);
            }
        }

        String instanceLocation = validationMessage.getInstanceLocation().toString();
        if (instanceLocation == null || instanceLocation.isEmpty() || "/".equals(instanceLocation)) {
            return presentNullRequiredProperty(event);
        }

        String instanceField = decodeInstanceLocation(instanceLocation);
        return event.containsKey(instanceField) ? instanceField : "$";
    }

    /**
     * Identify a required field that is present with a null value.
     *
     * <p>Some JSON Schema validators report a null type violation at the root
     * instance location. The canonical required-property order gives the logger
     * a stable, non-sensitive field label for that diagnostic.</p>
     *
     * @param event final flat event map being validated
     * @return the first required field with a null value, or {@code $} if none exists
     */
    private String presentNullRequiredProperty(Map<String, Object> event) {
        for (String requiredProperty : CANONICAL_SCHEMA.requiredProperties()) {
            if (event.containsKey(requiredProperty) && event.get(requiredProperty) == null) {
                return requiredProperty;
            }
        }
        return "$";
    }

    private String decodeInstanceLocation(String instanceLocation) {
        String trimmedLocation = instanceLocation.startsWith("/")
            ? instanceLocation.substring(1)
            : instanceLocation;

        return trimmedLocation
            .replace("~1", "/")
            .replace("~0", "~");
    }

    /**
     * Decode JSON Pointer escaping used in a schema-location property segment.
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
