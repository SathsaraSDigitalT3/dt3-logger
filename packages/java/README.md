# DT3 Commons Java API Contracts

The Java SDK provides synchronous structured logging with stdout, JSON Lines file, and HTTP JSON exporters. Events are always processed in the canonical order: masking, validation, then transport delivery.

## HTTP exporter

Configure the HTTP exporter through `SdkConfig`:

```java
SdkConfig config = new SdkConfig();
config.setServiceName("orders");
config.setServiceVersion("1.0.0");
config.setDeploymentEnvironment("production");
config.setExporter("http");
config.setHttpEndpoint("https://logs.example.com/v1/events");
// Maps to exporter.http.timeout; value is in milliseconds.
config.setHttpTimeout(5_000);
config.setHttpHeaders(Map.of("Authorization", "Bearer <token>"));

Logger logger = LoggerFactory.createLogger(config);
logger.info("Order created", Map.of("event.name", "ORDER_CREATED"));
```

The Java `setHttpTimeout(long)` API configures the canonical `exporter.http.timeout` value in milliseconds (default: `5000`). The transport sends each final event synchronously using `POST` with `Content-Type: application/json`. Custom headers are included except an attempted `Content-Type` override, because all exported DT3 events are JSON. Only 2xx responses are successful. Timeouts, connection failures, and non-2xx responses are transport failures; `failOpen` controls whether the logger swallows them (`true`, the default) or propagates `HttpTransportError` (`false`).

## OTLP/HTTP JSON Logs exporter

Configure the OTLP exporter with the canonical cross-language properties
`exporter = "otlp"`, `otlp.endpoint`, `otlp.timeout`, and `otlp.headers`:

```java
SdkConfig config = new SdkConfig();
config.setServiceName("orders");
config.setServiceVersion("1.0.0");
config.setDeploymentEnvironment("production");
config.setExporter("otlp");
config.setOtlpEndpoint("https://collector.example.com/v1/logs");
// Maps to otlp.timeout; value is in milliseconds (default: 10000).
config.setOtlpTimeout(10_000);
config.setOtlpHeaders(Map.of("Authorization", "Bearer <token>"));
config.setFailOpen(false);

Logger logger = LoggerFactory.createLogger(config);
logger.info("Order created", Map.of("event.name", "ORDER_CREATED", "order.id", "123"));
```

The SDK processes every event through masking and validation before mapping it
to the OTLP Logs JSON request shape. The transport sends one synchronous HTTP
`POST` per event, uses `Content-Type: application/json`, and accepts any 2xx
response as successful delivery. Custom headers are applied, but cannot
override the required content type. Endpoint, timeout, connection, and non-2xx
failures are represented by `OtlpTransportError`; `failOpen=true` (the default)
swallows these transport failures, while `failOpen=false` propagates them.
