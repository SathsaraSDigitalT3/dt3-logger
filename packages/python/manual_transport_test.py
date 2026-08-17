import json
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from dt3_sdk import create_logger


# =========================================================
# 1. FILE TRANSPORT
# =========================================================

print("\n=== 1. FILE TRANSPORT ===")

with tempfile.TemporaryDirectory() as tmp:
    log_file = Path(tmp) / "application.jsonl"

    logger = create_logger(
        {
            "service": "manual-test",
            "environment": "test",
            "exporter": "file",

            # IMPORTANT:
            # FileTransport expects the flat key "file.path"
            "file.path": str(log_file),
        }
    )

    logger.info(
        "File transport test",
        context={
            "user": "alice",
            "password": "secret123",
        },
    )

    logger.flush()
    logger.close()

    print("File exists:", log_file.exists())

    content = log_file.read_text(encoding="utf-8")

    print("File content:")
    print(content)

    event = json.loads(content.strip())

    print("Valid JSON:", isinstance(event, dict))
    print("Message:", event.get("message"))

    context = event.get("context", {})
    password_masked = '"password":"[REDACTED]"' in content
    print("Password masked:", password_masked)


# =========================================================
# 2. HTTP TRANSPORT
# =========================================================

print("\n=== 2. HTTP TRANSPORT ===")


class HTTPHandler(BaseHTTPRequestHandler):

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)

        print("HTTP request received")
        print("Path:", self.path)
        print("Content-Type:", self.headers.get("Content-Type"))

        try:
            payload = json.loads(body.decode("utf-8"))

            print("Valid JSON:", isinstance(payload, dict))
            print("Message:", payload.get("message"))

        except Exception as exc:
            print("Invalid JSON:", exc)

        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        pass


http_server = HTTPServer(("127.0.0.1", 8765), HTTPHandler)

http_thread = threading.Thread(
    target=http_server.serve_forever,
    daemon=True,
)

http_thread.start()

try:

    logger = create_logger(
        {
            "service": "manual-test",
            "environment": "test",
            "exporter": "http",

            # HTTP endpoint
            "http.endpoint": "http://127.0.0.1:8765/logs",
        }
    )

    logger.info(
        "HTTP transport test",
        context={
            "username": "alice",
        },
    )

    logger.flush()
    logger.close()

finally:
    http_server.shutdown()
    http_server.server_close()

print("HTTP transport test completed.")


# =========================================================
# 3. OTLP TRANSPORT
# =========================================================

print("\n=== 3. OTLP TRANSPORT ===")


class OTLPHandler(BaseHTTPRequestHandler):

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)

        print("OTLP request received")
        print("Path:", self.path)
        print("Content-Type:", self.headers.get("Content-Type"))

        try:
            payload = json.loads(body.decode("utf-8"))

            print("Valid JSON:", isinstance(payload, dict))

            print(
                "Top-level keys:",
                list(payload.keys()),
            )

            resource_logs = payload.get(
                "resourceLogs",
                [],
            )

            print(
                "resourceLogs present:",
                bool(resource_logs),
            )

        except Exception as exc:
            print("Invalid OTLP JSON:", exc)

        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        pass


otlp_server = HTTPServer(
    ("127.0.0.1", 8766),
    OTLPHandler,
)

otlp_thread = threading.Thread(
    target=otlp_server.serve_forever,
    daemon=True,
)

otlp_thread.start()

try:

    logger = create_logger(
        {
            "service": "manual-test",
            "environment": "test",
            "exporter": "otlp",

            # OTLP endpoint
            "otlp.endpoint": "http://127.0.0.1:8766/v1/logs",
        }
    )

    logger.info(
        "OTLP transport test",
        context={
            "user": "alice",
            "password": "secret123",
        },
    )

    logger.flush()
    logger.close()

finally:
    otlp_server.shutdown()
    otlp_server.server_close()

print("OTLP transport test completed.")


# =========================================================
# FINAL RESULT
# =========================================================

print("\n=== ALL MANUAL TRANSPORT TESTS COMPLETED ===")