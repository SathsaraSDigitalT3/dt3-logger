import json
import logging
from typing import Dict, Any, Optional
from datetime import datetime, timezone
import uuid

class LoggerImpl:
    def __init__(self, config: Dict[str, Any]):
        self.config = config
        self.exporter = config.get("exporter", "stdout")

    def _log(self, level: str, message: str, context: Optional[Dict[str, Any]] = None, error: Optional[Exception] = None):
        if context is None:
            context = {}
            
        event_name = context.get("event.name", "GENERIC_EVENT")
        
        log_event = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "severity": level,
            "message": message,
            "event.name": event_name,
            "schema.version": self.config.get("schema.version", "1.0.0"),
            "sdk.name": "dt3-python",
            "sdk.version": "0.1.0",
            "service.name": self.config.get("service.name", "unknown"),
            "service.version": self.config.get("service.version", "unknown"),
            "deployment.environment": self.config.get("deployment.environment", "unknown")
        }
        
        # Merge context
        for k, v in context.items():
            log_event[k] = v
            
        if error:
            log_event["error.type"] = type(error).__name__
            log_event["error.message"] = str(error)
            
        if self.exporter == "stdout":
            print(json.dumps(log_event))

    def debug(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        self._log("DEBUG", message, context)

    def info(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        self._log("INFO", message, context)

    def warn(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        self._log("WARN", message, context)

    def error(self, message: str, error: Optional[Exception] = None, context: Optional[Dict[str, Any]] = None) -> None:
        self._log("ERROR", message, context, error)

    def flush(self) -> None:
        pass
