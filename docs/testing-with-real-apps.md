# Test DT3 Commons with a real application

**End developers never clone this repository.**  
You only add DT3 Commons as a **library dependency** in your own FastAPI, Node, or Java app — the same way you use FastAPI, Express, or Spring.

SDK maintainers publish the packages. You install them with `pip`, `npm`, or Maven/Gradle.

---

## Install the libraries

Use the **published** packages when available (AWS CodeArtifact / PyPI / npm / Maven).  
Until those registries are live, use the **interim** library installs (still no clone — package managers fetch the artifact for you).

### Python (`dt3-commons`)

**When published (preferred):**

```bash
pip install dt3-commons
```

**Interim (library install from GitHub, no local clone):**

```bash
pip install "dt3-commons @ git+https://github.com/digitalt3/digitalt3-commons.git#subdirectory=packages/python"
```

Or install a release wheel URL if your team attaches one to a GitHub Release:

```bash
pip install https://github.com/digitalt3/digitalt3-commons/releases/download/v0.1.0/dt3_commons-0.1.0-py3-none-any.whl
```

### Node.js (`@digitalt3/commons`)

**When published (preferred):**

```bash
npm install @digitalt3/commons
```

**Interim (library install — pick one your team publishes):**

```bash
# GitHub Release tarball (recommended interim)
npm install https://github.com/digitalt3/digitalt3-commons/releases/download/v0.1.0/digitalt3-commons-0.1.0.tgz

# Or from GitHub Packages / private npm once configured
# npm install @digitalt3/commons --registry=https://npm.pkg.github.com
```

In `package.json` after install you should only see a normal dependency, for example:

```json
{
  "dependencies": {
    "@digitalt3/commons": "^0.1.0"
  }
}
```

### Java (`dt3-commons-api`)

**When published (preferred):**

```xml
<dependency>
  <groupId>com.digitalt3.commons</groupId>
  <artifactId>dt3-commons-api</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```gradle
implementation 'com.digitalt3.commons:dt3-commons-api:0.1.0'
```

Point Maven/Gradle at your AWS CodeArtifact (or Maven Central) repository — **do not** check out the SDK source.

**Interim:** use the same coordinates from **GitHub Packages** (or another Maven repo your team hosts). Add the repo to `pom.xml` / `settings.xml` as your platform team documents — still only a dependency, never a clone.

---

## 1. Python + FastAPI

```bash
pip install dt3-commons fastapi uvicorn
# or the interim pip line from above if registries are not ready
```

### Minimal app (`main.py`)

```python
from fastapi import FastAPI, Request
from dt3_sdk import EventEmitter, create_logger, logger_context

app = FastAPI()

logger = create_logger({
    "service.name": "my-fastapi-app",
    "service.version": "0.1.0",
    "deployment.environment": "local",
    "exporter": "stdout",
})
emitter = EventEmitter(logger)


@app.middleware("http")
async def add_dt3_context(request: Request, call_next):
    correlation = request.headers.get("x-correlation-id")
    with logger_context(correlation_id=correlation):
        response = await call_next(request)
        emitter.emit_api(
            "INCOMING_HTTP",
            f"{request.method} {request.url.path}",
            method=request.method,
            route=request.url.path,
            status_code=response.status_code,
        )
        return response


@app.get("/health")
def health():
    logger.info("health check", {"event.name": "HEALTH_CHECK"})
    return {"ok": True}
```

### Run and verify

```bash
uvicorn main:app --reload --port 8000
```

```bash
curl http://127.0.0.1:8000/health
curl -H "x-correlation-id: demo-123" http://127.0.0.1:8000/health
```

**Pass:** uvicorn prints JSON with `event.name`, `event.id`, `trace.id`, `span.id`, and (second call) `correlation.id` = `demo-123`.

---

## 2. Node.js + Express

```bash
npm install @digitalt3/commons express
# or the interim npm install from above
```

### Minimal app (`server.js`)

```js
const express = require('express');
const { createLogger, EventEmitter } = require('@digitalt3/commons');

const logger = createLogger({
  'service.name': 'my-node-app',
  'service.version': '0.1.0',
  'deployment.environment': 'local',
  exporter: 'stdout',
});
const emitter = new EventEmitter(logger);
const app = express();

app.use((req, res, next) => {
  logger
    .withContext({ correlationId: req.header('x-correlation-id') }, async () => {
      const started = Date.now();
      res.on('finish', () => {
        emitter.emitApi('INCOMING_HTTP', {
          message: `${req.method} ${req.path}`,
          'http.request.method': req.method,
          'http.route': req.path,
          'http.response.status_code': res.statusCode,
          'duration.ms': Date.now() - started,
        });
      });
      next();
    })
    .catch(next);
});

app.get('/health', (_req, res) => {
  logger.info('health check', { 'event.name': 'HEALTH_CHECK' });
  res.json({ ok: true });
});

app.listen(3000, () => console.log('listening on :3000'));
```

### Run and verify

```bash
node server.js
curl http://127.0.0.1:3000/health
```

**Pass:** JSON on stdout with `HEALTH_CHECK` / `INCOMING_HTTP`.

---

## 3. Java backend (Spring Boot or plain main)

Add the Maven/Gradle dependency (see [Install the libraries](#install-the-libraries)). Then use:

```java
import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.events.ApiEvents;
import com.digitalt3.commons.api.events.EventEmitter;

import java.util.Map;

SdkConfig config = new SdkConfig();
config.setServiceName("my-java-app");
config.setServiceVersion("0.1.0");
config.setDeploymentEnvironment("local");
config.setExporter("stdout");

Logger logger = LoggerFactory.createLogger(config);
EventEmitter emitter = new EventEmitter(logger);

try (LogContext.Scope ignored = logger.withContext(
        LogContext.builder().correlationId("demo-123").build())) {
    logger.info("health check", Map.of("event.name", "HEALTH_CHECK"));
    emitter.emit(ApiEvents.incomingHttp("GET", "/health", 200, 1.0));
}
```

### Run and verify

Start your app. **Pass:** stdout shows JSON with `HEALTH_CHECK` and `INCOMING_HTTP`.

Upgrade later with a normal dependency bump (`0.1.0` → newer version), then rebuild.

---

## What “good” looks like

Each log line is one JSON object:

| Field | Meaning |
| --- | --- |
| `event.name` | What happened (`HEALTH_CHECK`, `INCOMING_HTTP`, …) |
| `event.id` | Unique id (auto) |
| `trace.id` / `span.id` | Trace ids (auto if missing) |
| `correlation.id` | Request id you set or extracted |
| `service.name` | From your config |
| `severity` | `INFO`, `ERROR`, … |
| `message` | Human-readable text |

### Switch exporters (same app code)

| Goal | Config |
| --- | --- |
| Console (local) | `"exporter": "stdout"` |
| File | `"exporter": "file"` + path |
| HTTP ingest | `"exporter": "http"` + endpoint |
| OpenTelemetry | `"exporter": "otlp"` + endpoint |
| Several sinks | `"exporters": ["stdout", "file"]` |

---

## Common mistakes

| Problem | Fix |
| --- | --- |
| `ModuleNotFoundError: dt3_sdk` | Install `dt3-commons` into the **same** venv that runs uvicorn |
| `Cannot find module '@digitalt3/commons'` | Run `npm install` in the app; confirm the package is listed under `dependencies` |
| Maven “Could not find artifact” | Your app’s Maven settings must include the CodeArtifact / GitHub Packages repo; ask the platform team for the snippet |
| No JSON on screen | Wrong terminal, or exporter is not `stdout` |
| Old behavior after an SDK release | Bump the package version and reinstall / rebuild — do not pull SDK source into the app |

---

## What you should never do in an app repo

- Clone `digitalt3-commons` into your project  
- Use `file:../…/packages/node` or editable `pip install -e` against a local SDK checkout  
- Copy SDK source files into your service  

Only depend on the published (or interim-published) libraries.

## Related docs

- [Developer user stories (what you can use)](./developer-user-stories.md)
- [Getting started](./getting-started.md)
