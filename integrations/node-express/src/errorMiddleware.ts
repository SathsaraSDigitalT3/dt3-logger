import { Logger } from '@digitalt3/commons';

/** Create Express middleware that records and forwards application errors. */
export function createErrorMiddleware(logger: Logger) {
  return function dt3ErrorMiddleware(
    error: Error,
    request: { method?: string; route?: { path?: string } },
    _response: unknown,
    next: (error: Error) => void,
  ): void {
    logger.error('Unhandled request error', error, {
      'event.name': 'REQUEST_FAILED',
      attributes: {
        'http.method': request.method ?? 'UNKNOWN',
        'http.route': request.route?.path ?? 'unknown',
      },
    });
    next(error);
  };
}