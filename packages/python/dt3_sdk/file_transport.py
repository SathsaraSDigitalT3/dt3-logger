"""JSON Lines file transport for final DT3 structured log events."""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any, Mapping, TextIO


class FileTransport:
    """Append final DT3 log events to a configurable UTF-8 JSONL file."""

    def __init__(self, file_path: str | Path) -> None:
        """Create a file transport.

        Args:
            file_path: Destination path for append-only JSON Lines output.

        Raises:
            ValueError: If the configured path is empty.
            OSError: If the destination cannot be opened for appending.
        """
        if not file_path:
            raise ValueError("file.path must be configured for the file exporter")

        self._path = Path(file_path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._file: TextIO = self._path.open("a", encoding="utf-8", newline="\n")
        self._lock = threading.Lock()
        self._closed = False

    # PUBLIC_INTERFACE
    def export(self, event: Mapping[str, Any]) -> None:
        """Serialize and append one final structured DT3 log event as JSONL.

        Args:
            event: The already-masked and validation-processed canonical log event.

        Raises:
            RuntimeError: If the transport has already been closed.
            OSError: If the event cannot be written to the configured destination.
            TypeError: If the final event contains non-JSON-serializable values.
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("File transport is closed")
            serialized_event = json.dumps(
                dict(event), ensure_ascii=False, separators=(",", ":")
            )
            self._file.write(f"{serialized_event}\n")

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Flush file output written by this transport.

        Raises:
            RuntimeError: If the transport has already been closed.
            OSError: If the destination cannot be flushed.
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("File transport is closed")
            self._file.flush()

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Flush and close the underlying file resource.

        This operation is idempotent so callers may safely close a logger more
        than once during application shutdown.
        """
        with self._lock:
            if self._closed:
                return
            self._file.flush()
            self._file.close()
            self._closed = True
