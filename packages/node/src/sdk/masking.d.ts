/**
 * Sensitive-data masking utilities for DT3 Commons log events.
 *
 * The masking engine traverses nested objects and arrays, redacts values whose
 * field names are sensitive, and returns a copy so callers' source data is
 * never changed.
 */
export declare const DEFAULT_SENSITIVE_FIELDS: readonly ["password", "passwd", "pwd", "secret", "token", "access_token", "refresh_token", "authorization", "api_key", "apikey", "private_key", "credit_card", "card_number", "ssn", "nic", "national_id", "email", "phone"];
/**
 * Configuration for a {@link MaskingEngine}.
 */
export interface MaskingOptions {
    /** Additional field names to redact, matched case-insensitively. */
    sensitiveFields?: Iterable<string>;
    /** The value substituted for sensitive values. Defaults to `[REDACTED]`. */
    replacementValue?: string;
    /** Whether `mask` includes paths of fields that were redacted. */
    trackMaskedFields?: boolean;
    /** Whether masking is active. Defaults to `true`. */
    enabled?: boolean;
}
/**
 * Result returned from a masking operation.
 */
export interface MaskingResult<T> {
    /** A recursively copied value with sensitive field values redacted. */
    data: T;
    /** Paths of redacted fields, or an empty array when tracking is disabled. */
    maskedFields: string[];
}
/**
 * Recursively masks sensitive field values in data structures.
 */
export declare class MaskingEngine {
    private readonly sensitiveFields;
    private readonly replacementValue;
    private readonly trackMaskedFields;
    private readonly enabled;
    /**
     * Create a masking engine using default and caller-supplied sensitive fields.
     *
     * @param options - Masking behavior and additional sensitive field names.
     */
    constructor(options?: MaskingOptions);
    /**
     * Return a recursively copied, masked version of the supplied data.
     *
     * @typeParam T - The input data type.
     * @param data - Object, array, primitive, or nested combination to process.
     * @returns The copied data and optional paths of masked fields.
     */
    mask<T>(data: T): MaskingResult<T>;
    private maskValue;
    private copyValue;
    private isPlainObject;
}
