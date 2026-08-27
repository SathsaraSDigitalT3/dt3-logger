import { Logger } from '../api/Logger';
import { LogEvent } from '../api/types';
import {
  AiEventFields,
  AiEventName,
  ApiEventFields,
  ApiEventName,
  buildAiEvent,
  buildAiRequestEvent,
  buildAiResponseEvent,
  buildApiEvent,
  buildDatabaseEvent,
  buildMessagingEvent,
  DatabaseEventFields,
  DatabaseEventName,
  MessagingEventFields,
  MessagingEventName,
} from './events';

/**
 * Typed emitter that routes domain/AI builder outputs through `Logger.event`.
 *
 * This complements the generic `Logger.event` API without replacing it.
 */
export class EventEmitter {
  private readonly logger: Logger;

  /**
   * Create an emitter bound to a logger.
   *
   * @param logger - Destination logger that owns the processing pipeline.
   */
  constructor(logger: Logger) {
    this.logger = logger;
  }

  /**
   * Emit a canonical or builder-produced event through the logger pipeline.
   *
   * @param event - Partial or full LogEvent. Must include `message` and usually `event.name`.
   */
  public emit(event: Partial<LogEvent> | Record<string, unknown>): void {
    this.logger.event(event as LogEvent);
  }

  /**
   * Build and emit an API / HTTP event.
   *
   * @param eventName - `INCOMING_HTTP` or `OUTGOING_HTTP`.
   * @param fields - HTTP attributes and optional message/severity overrides.
   */
  public emitApi(eventName: ApiEventName, fields: ApiEventFields = {}): void {
    this.emit(buildApiEvent(eventName, fields));
  }

  /**
   * Build and emit a database event.
   *
   * @param eventName - Database lifecycle event name.
   * @param fields - Database attributes and optional message/severity overrides.
   */
  public emitDb(eventName: DatabaseEventName, fields: DatabaseEventFields = {}): void {
    this.emit(buildDatabaseEvent(eventName, fields));
  }

  /**
   * Build and emit a messaging / worker event.
   *
   * @param eventName - Messaging event name.
   * @param fields - Messaging attributes and optional message/severity overrides.
   */
  public emitMessaging(eventName: MessagingEventName, fields: MessagingEventFields = {}): void {
    this.emit(buildMessagingEvent(eventName, fields));
  }

  /**
   * Build and emit an AI / Kavia event.
   *
   * @param eventName - AI lifecycle event name.
   * @param fields - Kavia attributes and optional message/severity overrides.
   */
  public emitAi(eventName: AiEventName, fields: AiEventFields = {}): void {
    this.emit(buildAiEvent(eventName, fields));
  }

  /**
   * Emit an AI request (prompt-side) event.
   */
  public emitAiRequest(fields: AiEventFields = {}): void {
    this.emit(buildAiRequestEvent(fields));
  }

  /**
   * Emit an AI response event correlated by request id.
   */
  public emitAiResponse(fields: AiEventFields = {}): void {
    this.emit(buildAiResponseEvent(fields));
  }
}
