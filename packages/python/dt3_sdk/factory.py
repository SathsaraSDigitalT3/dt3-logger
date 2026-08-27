from typing import Any, Dict

from .impl.logger_impl import LoggerImpl


# PUBLIC_INTERFACE
def create_logger(config: Dict[str, Any]) -> LoggerImpl:
    """Create a logger and report construction failures through its ErrorHandler."""
    return LoggerImpl(config)
