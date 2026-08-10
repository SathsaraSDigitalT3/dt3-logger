from typing import Dict, Any
from .impl.logger_impl import LoggerImpl

def create_logger(config: Dict[str, Any]) -> LoggerImpl:
    return LoggerImpl(config)
