/**
 * Sensitive-data masking utilities for DT3 Commons log events.
 *
 * The masking engine traverses nested objects and arrays, redacts values whose
 * field names are sensitive, and returns a copy so callers' source data is
 * never changed.
 */

export const DEFAULT_SENSITIVE_FIELDS = [
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
] as const;

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
export class MaskingEngine {
  private readonly sensitiveFields: ReadonlySet<string>;
  private readonly replacementValue: string;
  private readonly trackMaskedFields: boolean;
  private readonly enabled: boolean;

  /**
   * Create a masking engine using default and caller-supplied sensitive fields.
   *
   * @param options - Masking behavior and additional sensitive field names.
   */
  constructor(options: MaskingOptions = {}) {
    const fields = new Set<string>();

    for (const field of DEFAULT_SENSITIVE_FIELDS) {
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
  public mask<T>(data: T): MaskingResult<T> {
    if (!this.enabled) {
      return {
        data: this.copyValue(data) as T,
        maskedFields: [],
      };
    }

    const result = this.maskValue(data, '');
    return {
      data: result.data as T,
      maskedFields: this.trackMaskedFields ? result.maskedFields : [],
    };
  }

  private maskValue(value: unknown, path: string): MaskingResult<unknown> {
    if (Array.isArray(value)) {
      const maskedFields: string[] = [];
      const data = value.map((item, index) => {
        const result = this.maskValue(item, `${path}[${index}]`);
        maskedFields.push(...result.maskedFields);
        return result.data;
      });

      return { data, maskedFields };
    }

    if (this.isPlainObject(value)) {
      const maskedFields: string[] = [];
      const data: Record<string, unknown> = {};

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

  private copyValue(value: unknown): unknown {
    if (Array.isArray(value)) {
      return value.map((item) => this.copyValue(item));
    }

    if (this.isPlainObject(value)) {
      return Object.fromEntries(
        Object.entries(value).map(([key, childValue]) => [key, this.copyValue(childValue)]),
      );
    }

    return value;
  }

  private isPlainObject(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }
}
