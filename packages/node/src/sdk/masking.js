"use strict";
/**
 * Sensitive-data masking utilities for DT3 Commons log events.
 *
 * The masking engine traverses nested objects and arrays, redacts values whose
 * field names are sensitive, and returns a copy so callers' source data is
 * never changed.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.MaskingEngine = exports.DEFAULT_SENSITIVE_FIELDS = void 0;
exports.DEFAULT_SENSITIVE_FIELDS = [
    'password',
    'passwd',
    'pwd',
    'secret',
    'token',
    'access_token',
    'refresh_token',
    'authorization',
    'api_key',
    'apikey',
    'private_key',
    'credit_card',
    'card_number',
    'ssn',
    'nic',
    'national_id',
    'email',
    'phone',
];
/**
 * Recursively masks sensitive field values in data structures.
 */
class MaskingEngine {
    sensitiveFields;
    replacementValue;
    trackMaskedFields;
    enabled;
    /**
     * Create a masking engine using default and caller-supplied sensitive fields.
     *
     * @param options - Masking behavior and additional sensitive field names.
     */
    constructor(options = {}) {
        const fields = new Set();
        for (const field of exports.DEFAULT_SENSITIVE_FIELDS) {
            fields.add(field.toLocaleLowerCase());
        }
        for (const field of options.sensitiveFields ?? []) {
            if (typeof field === 'string') {
                fields.add(field.toLocaleLowerCase());
            }
        }
        this.sensitiveFields = fields;
        this.replacementValue = options.replacementValue ?? '[REDACTED]';
        this.trackMaskedFields = options.trackMaskedFields ?? false;
        this.enabled = options.enabled ?? true;
    }
    // PUBLIC_INTERFACE
    /**
     * Return a recursively copied, masked version of the supplied data.
     *
     * @typeParam T - The input data type.
     * @param data - Object, array, primitive, or nested combination to process.
     * @returns The copied data and optional paths of masked fields.
     */
    mask(data) {
        if (!this.enabled) {
            return {
                data: this.copyValue(data),
                maskedFields: [],
            };
        }
        const result = this.maskValue(data, '');
        return {
            data: result.data,
            maskedFields: this.trackMaskedFields ? result.maskedFields : [],
        };
    }
    maskValue(value, path) {
        if (Array.isArray(value)) {
            const maskedFields = [];
            const data = value.map((item, index) => {
                const result = this.maskValue(item, `${path}[${index}]`);
                maskedFields.push(...result.maskedFields);
                return result.data;
            });
            return { data, maskedFields };
        }
        if (this.isPlainObject(value)) {
            const maskedFields = [];
            const data = {};
            for (const [key, childValue] of Object.entries(value)) {
                const childPath = path ? `${path}.${key}` : key;
                if (this.sensitiveFields.has(key.toLocaleLowerCase())) {
                    data[key] = this.replacementValue;
                    maskedFields.push(childPath);
                    continue;
                }
                const result = this.maskValue(childValue, childPath);
                data[key] = result.data;
                maskedFields.push(...result.maskedFields);
            }
            return { data, maskedFields };
        }
        return { data: this.copyValue(value), maskedFields: [] };
    }
    copyValue(value) {
        if (Array.isArray(value)) {
            return value.map((item) => this.copyValue(item));
        }
        if (this.isPlainObject(value)) {
            return Object.fromEntries(Object.entries(value).map(([key, childValue]) => [key, this.copyValue(childValue)]));
        }
        return value;
    }
    isPlainObject(value) {
        return value !== null && typeof value === 'object' && !Array.isArray(value);
    }
}
exports.MaskingEngine = MaskingEngine;
