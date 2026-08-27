/**
 * Stable, machine-readable classification for SDK-internal failures.
 */
export enum Dt3ErrorCode {
  ConfigurationInvalid = 'DT3_CONFIG_INVALID',
  ExporterUnsupported = 'DT3_EXPORTER_UNSUPPORTED',
  FileWriteFailed = 'DT3_FILE_WRITE_FAILED',
  TransportUnavailable = 'DT3_TRANSPORT_UNAVAILABLE',
  TransportTimeout = 'DT3_TRANSPORT_TIMEOUT',
  TransportRejected = 'DT3_TRANSPORT_REJECTED',
  TransportClosed = 'DT3_TRANSPORT_CLOSED',
  SerializationFailed = 'DT3_SERIALIZATION_FAILED',
  MaskingFailed = 'DT3_MASKING_FAILED',
  ValidationFailed = 'DT3_VALIDATION_FAILED',
  BatchOverflow = 'DT3_BATCH_OVERFLOW',
  BatchAborted = 'DT3_BATCH_ABORTED',
  LifecycleClosed = 'DT3_LIFECYCLE_CLOSED',
  Unknown = 'DT3_UNKNOWN',
}

/**
 * Pipeline stage that produced an SDK failure.
 */
export enum Dt3ErrorPhase {
  Configuration = 'configuration',
  Enrichment = 'enrichment',
  Masking = 'masking',
  Validation = 'validation',
  Batching = 'batching',
  Delivery = 'delivery',
  Lifecycle = 'lifecycle',
}

/**
 * Options used to construct a classified DT3 error.
 */
export interface Dt3ErrorOptions {
  code?: Dt3ErrorCode;
  retryable?: boolean;
  phase?: Dt3ErrorPhase;
  details?: Record<string, unknown>;
  cause?: unknown;
}

/**
 * Base class for all DT3 SDK-internal errors.
 */
export class Dt3Error extends Error {
  public readonly code: Dt3ErrorCode;
  public readonly retryable: boolean;
  public readonly phase: Dt3ErrorPhase;
  public readonly details: Readonly<Record<string, unknown>>;

  /**
   * Create a classified SDK error.
   *
   * @param message - Sanitized failure description.
   * @param options - Canonical classification, diagnostic details, and cause.
   */
  constructor(message: string, options: Dt3ErrorOptions = {}) {
    super(message);
    this.name = new.target.name;
    this.code = options.code ?? Dt3ErrorCode.Unknown;
    this.retryable = options.retryable ?? false;
    this.phase = options.phase ?? Dt3ErrorPhase.Delivery;
    this.details = Object.freeze({ ...(options.details ?? {}) });

    if (options.cause !== undefined) {
      (this as Error & { cause?: unknown }).cause = options.cause;
    }

    Object.setPrototypeOf(this, new.target.prototype);
  }

  // PUBLIC_INTERFACE
  /**
   * Return canonical log-event fields describing this error.
   *
   * @returns Error fields compatible with the canonical event schema.
   */
  public toFields(): Record<string, unknown> {
    return {
      'error.type': this.name,
      'error.message': this.message,
      'error.code': this.code,
      'error.retryable': this.retryable,
    };
  }
}

/**
 * Invalid SDK configuration detected while constructing a logger.
 */
export class Dt3ConfigurationError extends Dt3Error {
  /**
   * Create a non-retryable configuration failure.
   *
   * @param message - Sanitized configuration failure description.
   */
  constructor(message: string) {
    super(message, {
      code: Dt3ErrorCode.ConfigurationInvalid,
      retryable: false,
      phase: Dt3ErrorPhase.Configuration,
    });
  }
}

/**
 * A transport could not deliver an event.
 */
export class Dt3TransportError extends Dt3Error {
  /**
   * Create a transport failure.
   *
   * @param message - Sanitized transport failure description.
   * @param options - Optional specific transport classification.
   */
  constructor(
    message: string,
    options: Pick<Dt3ErrorOptions, 'code' | 'retryable' | 'cause'> = {},
  ) {
    super(message, {
      code: options.code ?? Dt3ErrorCode.TransportUnavailable,
      retryable: options.retryable ?? true,
      phase: Dt3ErrorPhase.Delivery,
      cause: options.cause,
    });
  }
}

/**
 * A masking operation could not process caller-provided context.
 */
export class Dt3MaskingError extends Dt3Error {
  /**
   * Create a masking failure.
   *
   * @param message - Sanitized masking failure description.
   * @param cause - Original masking failure.
   */
  constructor(message: string, cause?: unknown) {
    super(message, {
      code: Dt3ErrorCode.MaskingFailed,
      retryable: false,
      phase: Dt3ErrorPhase.Masking,
      cause,
    });
  }
}

/**
 * A logger, batcher, or transport was used after entering its terminal state.
 */
export class Dt3LifecycleError extends Dt3Error {
  /**
   * Create a lifecycle failure.
   *
   * @param message - Sanitized lifecycle failure description.
   */
  constructor(message: string) {
    super(message, {
      code: Dt3ErrorCode.LifecycleClosed,
      retryable: false,
      phase: Dt3ErrorPhase.Lifecycle,
    });
  }
}
